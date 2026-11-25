# 直播间弹幕 V2 接口设计

## 公共约定

HTTP 沿用同源、Bearer 鉴权与账号当前会话校验，POST 继续校验 CSRF。目标房间使用标准小写 UUID v4。服务端从安全上下文获取账号和会话。成功与错误响应携带 `Cache-Control: no-store`。弹幕时间为 UTC ISO 8601，固定三位小数秒；正整数序号使用规范十进制字符串，初始游标和水位可以为 `"0"`。Vue 用整数比较语义处理序号。 [房间要求](../../room/api.md#查询房间详情) · [鉴权约定](../../auth/api.md#公共约定)

| 方法和路径 | 用途 | 成功结果 |
| --- | --- | --- |
| `POST /api/v1/rooms/{roomId}/ws-ticket` | 为当前会话签发一次性连接 Ticket | `200`，`ticket` 与 `expiresIn` |
| `POST /api/v1/rooms/{roomId}/messages` | 单次提交弹幕 | `202`，提交已获得 Kafka 写入确认 |
| `GET /api/v1/rooms/{roomId}/messages/submissions/{submissionId}` | 查询本人当前会话的提交结果 | `200`，当前可见回执 |
| `GET /api/v1/rooms/{roomId}/messages` | 读取最近历史或按序号范围分页 | `200`，当前快照内的历史页 |
| `/ws/rooms` | 首帧 Ticket 鉴权，实时接收和同步通知 | `ready` 确认就绪 |

Ticket 请求、响应、30 秒有效期沿用当前协议。客户端每次连线重新申请。 [现有 Ticket HTTP 契约](../api.md#申请接收票据) · [票据存储](../storage.md#redis-ticket)

### 房间存在性判定

Ticket 签发、WebSocket 兑票、新弹幕发送和历史查询，通过鉴权和 UUID 校验后共用房间存在性判断。签发先确认房间，再写 Ticket。兑票仍先原子消费 Ticket、核对会话，再确认绑定的房间。发送先读取已有预约，新预约在房间检查通过后才继续。历史先确认房间，再进入 MySQL 历史快照。回执继续按账号、会话、房间和提交标识查询。 [缓存与回源规则](../../room/storage.md)

| 房间检查结果 | HTTP | WebSocket 首帧处理 |
| --- | --- | --- |
| 确认存在 | 继续当前操作 | 等待订阅就绪并发送 `ready` |
| 确认不存在 | `404 ROOM_NOT_FOUND` | 发送 `ROOM_NOT_FOUND` 错误，使用关闭码 `1008`。 |
| 回源额度耗尽、等待超时或依赖故障 | `503 ROOM_MESSAGES_UNAVAILABLE` | 发送 `ROOM_MESSAGES_UNAVAILABLE` 错误，使用关闭码 `1011`。再次连接申请新 Ticket。 |

发送在房间检查处结束时，尚未建立预约、占用账号冷却或发布 Kafka 消息。本机回源容量拒绝和回填等待超时归还熔断许可，跳过有效结果统计。实际 MySQL 故障继续计入发送依赖失败。发出的 `503` 沿用现有错误正文。 [发送熔断](../../resilience/design.md#发送熔断) · [HTTP 错误](#http-错误)

## 发送弹幕

请求只接受 `clientMessageId` 与 `content` 两个字符串，无查询参数。客户端为新提交生成小写 UUID v4。正文沿用 V1 的单行文本规则，校验原始字符后去除首尾 Unicode 空白，保留内部空白，长度为 1–100 个码点。重复键、未知字段和无效 JSON 均拒绝。 [输入约定](../api.md#发送弹幕)

```json
{
  "clientMessageId": "1c3d6dfa-58a1-42a3-9141-478c35dad5bf",
  "content": "LiveFlow"
}
```

`202 Accepted` 带 `Location` 响应头，指向 `/api/v1/rooms/{roomId}/messages/submissions/{submissionId}`。响应使用下表字段。新版 Vue 和 API 同步切换，旧 `retryUntil` 字段随发送重试协议移除。通过发送熔断检查后，同一标识和正文的已入队预约可以读回原 `202`。 [Kafka 写入确认](event-contract.md#生产和确认) · [HTTP 202 语义](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3) · [发布与回退](../../resilience/deployment-and-operations.md#发布与回退)

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `submissionId` | string | 预约公开 UUID，也是回执查询标识。 |
| `status` | string | 固定为 `queued`，表示 Kafka 已确认入队。正式接收由回执确认。 |
| `receiptExpiresAt` | string | UTC 时间，首次 Redis 许可后 30 分钟。 |

```json
{
  "submissionId": "4154ce3d-d846-4bd5-8fa9-a79b156a11ed",
  "status": "queued",
  "receiptExpiresAt": "2026-11-24T09:00:00.000Z"
}
```

鉴权和输入通过后，先检查发送熔断许可，再访问发送专用 Redis 及 MySQL。暂停或半开名额已用尽时返回 `503 ROOM_MESSAGES_UNAVAILABLE`，请求在房间存在性检查和建立预约前结束。 [发送熔断](../../resilience/design.md#发送熔断) · [账号冷却](../../resilience/api.md)

三实例使用同一发送接口。读取已有提交绑定后，创建新预约前按 `roomId` 检查目标提交分区的接入状态。目标分区长期不可处理或已阻断时，返回 `503 ROOM_MESSAGES_UNAVAILABLE`，本次未创建预约，也未占用账号冷却。通过检查后仍以 Broker 确认为 `202` 的依据。 [三实例分区交接](event-contract.md#三实例分区交接与接入判断)

### 发送交互

- 用户点击发送后冻结本次正文和客户端标识。一轮操作最多发出一次 POST，提交期间禁用重复点击。可在提交前续期 Access Token。收到明确的过期响应时结束本次操作，认证封装禁止自动重发该 POST。移除「重试原弹幕」操作。
- 收到 `429` 时保留输入，按 `Retry-After` 显示账号冷却。已知当前提交未发往 Kafka 时提示「弹幕暂未发送」并保留草稿。用户主动发起新的提交时生成新的客户端标识。
- 已入队时显示「已入队，等待发送确认」。结果未知时显示「发送结果未确认，消息可能已送达」，保留独立的正文快照。服务端提供 `submissionId` 时可用 GET 查询；网络中断且未取得查询标识时，可通过实时列表或历史观察结果。

## 查询提交回执

`submissionId` 为标准小写 UUID v4。接口只查询当前认证账号、会话和房间的回执，无请求体或查询参数。回执由 Kafka 已提交的终态投影。读到 `accepted` 时可确认发送完成；`rejected` 给出业务错误码；`unconfirmed` 表示当前查不到终态。该结果也适用于无权查看的标识，避免透露其他会话的提交。 [Redis 回执投影](storage.md#回执投影)

请求可落到任意实例。Redis 可读时返回当前可见终态或 `unconfirmed`，消费者交接期间也可查询已投影的回执。Redis 查询失败时返回 `503 ROOM_MESSAGES_UNAVAILABLE`。当前会话确认失败时沿用认证错误。

| `status` | 字段 |
| --- | --- |
| `accepted` | `submissionId`、`status`、`messageId`、`roomSeq`、`sentAt` |
| `rejected` | `submissionId`、`status`、`code`，取值为 `MESSAGE_ID_CONFLICT` 或 `MESSAGE_SUBMISSION_EXPIRED` |
| `unconfirmed` | `submissionId`、`status` |

```json
{
  "submissionId": "4154ce3d-d846-4bd5-8fa9-a79b156a11ed",
  "status": "accepted",
  "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
  "roomSeq": "41",
  "sentAt": "2026-11-24T08:30:00.123Z"
}
```

前端获得 `202` 后可轮询回执，初始间隔为 500 毫秒，未确认时退避并限制同一提交只保留一个查询。连续查询 10 秒仍未确认时，显示「待确认」并提供手动查询。`MESSAGE_SUBMISSION_UNKNOWN` 带有回执标识时提供手动查询。到达回执截止、离开房间或会话切换时停止追踪。回执查询使用 GET。

## 查询历史

`GET /api/v1/rooms/{roomId}/messages` 要求有效 Bearer 和当前登录会话。路径中的 `roomId` 使用标准小写 UUID v4。响应携带 `Cache-Control: no-store`。每个查询参数只允许出现一次。

| 参数 | 类型与范围 | 语义 |
| --- | --- | --- |
| `limit` | 整数 1–100，缺省 100 | 本页条数 |
| `afterSeq` | 可选，非负整数的十进制字符串 | 只读更大的序号。缺省时读取最近窗口 |
| `throughSeq` | 可选，非负整数的十进制字符串 | 只在有 `afterSeq` 时使用，且不小于它。用于限制读取上界，缺省时使用当前最大落库序号 |

序号参数上限为 `9223372036854775806`。未知参数、重复参数、非规范序号、越界或不合法组合返回 `400 INVALID_REQUEST`。省略 `afterSeq` 时选最近一页，并按序号升序返回。指定 `afterSeq` 时按 `afterSeq < roomSeq <= 响应 throughSeq` 升序返回。两种模式均只返回仍在保留期内的记录。 [范围 SQL](database.md#历史查询和分页)

| 响应字段 | 语义 |
| --- | --- |
| `items` | 升序的历史弹幕，可为空数组 |
| `latestStoredSeq` | 十进制字符串。本次快照中已经成功落库的最大序号。房间从未写入时为 `"0"`，清理后仍保留该值 |
| `throughSeq` | 十进制字符串。本页实际读取上限。请求指定上限时取它与 `latestStoredSeq` 中的较小值，否则等于 `latestStoredSeq` |
| `nextAfterSeq` | 可空十进制字符串。范围模式还有下一页时，取本页最后一条实际记录的序号。最近模式或已读完时为 `null` |

范围分页继续时，将上一页的 `nextAfterSeq` 作为 `afterSeq`，沿用返回的 `throughSeq`。固定上限只约束序号范围，每次请求都读取当时的 MySQL 快照。后来才落在已读游标之前的记录，可通过重新读取相关范围或最近窗口找到。游标落在已清理范围时跳过已过期记录。游标大于或等于本页实际读取上限时，返回 `200`、空数组和 `nextAfterSeq: null`。

三实例从共享 MySQL 读取同一房间的已提交快照。消费者交接、普通积压或缺号时返回当前可读页。持久历史分区故障或历史依赖不可用时返回 `503 ROOM_MESSAGES_UNAVAILABLE`，客户端保留当前列表并按恢复规则处理。序号缺口及单条写入冲突继续返回 `200`。 [历史快照与阻断](database.md#历史查询和分页)

客户端以 `afterSeq=100&throughSeq=103` 请求时，即使 101 和 102 尚未入库，仍可读到已落库的 103。

```json
{
  "items": [
    {
      "roomSeq": "103",
      "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
      "senderName": "小叶",
      "content": "LiveFlow",
      "sentAt": "2026-11-24T08:30:00.123Z"
    }
  ],
  "latestStoredSeq": "103",
  "throughSeq": "103",
  "nextAfterSeq": null
}
```

## WebSocket 接收协议

生产使用同源 `wss`，本地单实例使用 Vite 代理。三实例通过同源代理签发 Ticket 和建立 WebSocket，两次请求可以落在不同实例。验票后等待房间订阅就绪，先排入 `ready` 再允许后续实时帧。连接沿用来源校验、心跳及当前会话复查。 [V1 连接和首帧](../api.md#websocket-接收协议) · [房间订阅](storage.md#房间频道与订阅)

| 帧 | 方向 | 字段与处理 |
| --- | --- | --- |
| `auth` | 客户端 → 服务端 | 首帧，含 `ticket` |
| `ready` | 服务端 → 客户端 | 含 `roomId`，开始初始历史读取或重连补拉 |
| `message` | 服务端 → 客户端 | 附带全部公开弹幕字段 |
| `history_progress` | 服务端 → 客户端 | 含十进制字符串 `latestStoredSeq`。超过浏览器已见最大序号时触发最近窗口查询 |
| `resync_required` | 服务端 → 客户端 | 含 `roomId`。订阅恢复后触发最近窗口查询 |
| `error` | 服务端 → 客户端 | 含 `code` 和 `message`，适用时随后关闭连接 |

```json
{
  "type": "message",
  "roomSeq": "41",
  "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
  "senderName": "小叶",
  "content": "LiveFlow",
  "sentAt": "2026-11-24T08:30:00.123Z"
}
```

历史进度通知格式如下。迟到写入低于现有最大序号时，等待该消息的实时帧或下次最近窗口查询补入。

```json
{
  "type": "history_progress",
  "latestStoredSeq": "103"
}
```

## 弹幕数据

| 字段 | 约定 |
| --- | --- |
| `roomSeq` | 房间内独立的正整数序号，供实时和历史合并 |
| `messageId` | Kafka 正式接收时生成的标准小写 UUID v4 |
| `senderName` | 消息中的显示名快照，有昵称时用昵称，缺省时保留原账号拼写 |
| `content` | 服务端规范化的纯文本 |
| `sentAt` | 受理时间，页面用本地时区格式化为 `HH:mm:ss` |

## HTTP 错误

错误正文使用 `code`、`message`。

| HTTP | 错误码 | 行为 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 房间、提交标识、正文或查询参数格式无效 |
| `401`、`403` | 沿用认证及 CSRF 错误 | 发送遇到凭证错误时结束本次操作 |
| `404` | `ROOM_NOT_FOUND` | 目标房间不存在 |
| `409` | `MESSAGE_ID_CONFLICT` | 相同提交标识已绑定不同正文 |
| `429` | `MESSAGE_RATE_LIMITED` | 额度用尽，返回整数秒 `Retry-After` |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | 当前会话无法确认 |
| `503` | `ROOM_MESSAGES_UNAVAILABLE` | 发送因熔断、依赖故障或本机容量拒绝在生产前停止。若 Redis 预约已登记，冷却按原截止到期。回执和历史读取各自不可用时也使用此码 |
| `503` | `MESSAGE_SUBMISSION_UNKNOWN` | Kafka 提交结果未知，或同一绑定仍待确认。当前会话的预约已知时，响应体同时附 `submissionId` 与 `receiptExpiresAt` |

发送 `503` 不附 `Retry-After`。HTTP 超时、连接中断或来源不明确的网关响应按结果未知处理。收到 `202` 后，正式流程发现冲突或过期时通过终态回执报告。 [提交绑定](storage.md#账号额度和提交绑定)

结果未知且可查询时的 `503` 响应体示例。无法确定预约时只返回 `code` 和 `message`。

```json
{
  "code": "MESSAGE_SUBMISSION_UNKNOWN",
  "message": "发送结果未确认，消息可能已送达",
  "submissionId": "4154ce3d-d846-4bd5-8fa9-a79b156a11ed",
  "receiptExpiresAt": "2026-11-24T09:00:00.000Z"
}
```

## 前端合并与恢复

1. 首次 `ready` 后立即合并收到的实时帧，同时发起最近 100 条历史的异步查询。房间显示的最后一条为 100 时，收到 103 即可展示。迟到的 101 从实时帧收到或从下次历史刷新读到时，仍在最近 100 条内就插入对应位置。
2. 列表按 `roomSeq` 升序保留最新 100 条，使用 `messageId` 去重，每个房间序号最多显示一项。两条互相冲突的实时记录先保留已展示项。历史返回了冲突键对应的持久记录时，以该记录替换本地冲突项。稀疏历史页只合并实际返回的记录，保留未被替换且仍在窗口内的实时消息。
3. 初次就绪、重连就绪、收到 `resync_required` 或收到高于浏览器已见最大序号的 `history_progress` 时，查询最近窗口。已见最大序号来自实际收到的实时和历史记录。每个活跃连接最多有一个历史请求在途，期间的多个刷新提示合并一次。成功读完当前页即结束本轮，缺号本身不会发起循环补拉。
4. 历史暂不可用时，当前列表和实时展示继续工作。仍在当前房间和连接时，历史 GET 可按有上限的退避策略恢复。连接异常时按带抖动的退避策略重连，每次重新申请 Ticket。恢复接收链路不会自动发起发送 POST。
5. 换房、切换账号或会话、页面卸载时重置列表和请求。重连保留当前房间与会话的列表，后续响应需要通过房间、会话及连接代次校验。页面沿用底部跟随、上翻阅读位置、草稿和输入法交互。 [历史写入](database.md#历史写入和水位)
