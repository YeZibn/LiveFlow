# 直播间弹幕 V2 接口设计

## 公共约定

HTTP 沿用同源、Bearer 鉴权与账号当前会话校验，POST 继续校验 CSRF。目标房间使用标准小写 UUID v4。服务端从安全上下文获取账号和会话。成功与错误响应携带 `Cache-Control: no-store`。弹幕时间为 UTC ISO 8601，固定三位小数秒；正整数序号使用规范十进制字符串，初始游标和水位可以为 `"0"`。Vue 用整数比较语义处理序号。 [房间要求](../../room/api.md#查询房间详情) · [鉴权约定](../../auth/api.md#公共约定)

| 方法和路径 | 用途 | 成功结果 |
| --- | --- | --- |
| `POST /api/v1/rooms/{roomId}/ws-ticket` | 为当前会话签发一次性连接 Ticket | `200`，`ticket` 与 `expiresIn` |
| `POST /api/v1/rooms/{roomId}/messages` | 单次提交弹幕 | `202`，提交已获得 Kafka 写入确认 |
| `GET /api/v1/rooms/{roomId}/messages/submissions/{submissionId}` | 查询本人当前会话的提交结果 | `200`，当前可见回执 |
| `GET /api/v1/rooms/{roomId}/messages` | 读取最近历史或按序补拉 | `200`，当前快照内的历史页 |
| `/ws/rooms` | 首帧 Ticket 鉴权，实时接收和同步通知 | `ready` 确认就绪 |

Ticket 请求、响应、30 秒有效期沿用当前协议。客户端每次连线重新申请。 [现有 Ticket HTTP 契约](../api.md#申请接收票据) · [票据存储](../storage.md#redis-ticket)

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

### 发送交互

- 用户点击发送后冻结本次正文和客户端标识。一轮操作最多发出一次 POST，提交期间禁用重复点击。可在提交前续期 Access Token。收到明确的过期响应时结束本次操作，认证封装禁止自动重发该 POST。移除「重试原弹幕」操作。
- 收到 `429` 时保留输入，按 `Retry-After` 显示账号冷却。已知当前提交未发往 Kafka 时提示「弹幕暂未发送」并保留草稿。用户主动发起新的提交时生成新的客户端标识。
- 已入队时显示「已入队，等待发送确认」。结果未知时显示「发送结果未确认，消息可能已送达」，保留独立的正文快照。服务端提供 `submissionId` 时可用 GET 查询；网络中断且未取得查询标识时，可通过实时列表或历史观察结果。

## 查询提交回执

`submissionId` 为标准小写 UUID v4。接口只查询当前认证账号、会话和房间的回执，无请求体或查询参数。回执由 Kafka 已提交的终态投影。读到 `accepted` 时可确认发送完成；`rejected` 给出业务错误码；`unconfirmed` 表示当前查不到终态。该结果也适用于无权查看的标识，避免透露其他会话的提交。 [Redis 回执投影](storage.md#回执投影)

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

GET 请求要求有效 Bearer，可读取存在的房间。每个查询参数只允许出现一次。

| 参数 | 类型与范围 | 语义 |
| --- | --- | --- |
| `limit` | 整数 1–100，缺省 100 | 本页条数 |
| `afterSeq` | 可选，非负整数的十进制字符串 | 只读更大的序号。缺省时读取最近窗口 |
| `throughSeq` | 可选，非负整数的十进制字符串 | 只在有 `afterSeq` 时使用，且不小于它。用于固定本轮补拉目标，缺省时使用 `max(afterSeq, storedThrough)` |

序号参数上限为 `9223372036854775806`。拒绝未知参数、前导零、负值、越界或不合法组合。首次读取最近窗口时，响应将最近一页按序号升序返回。补拉模式按 `afterSeq < roomSeq <= min(throughSeq, storedThrough)` 升序读取。 [范围 SQL](database.md#历史查询和分页)

| 响应字段 | 语义 |
| --- | --- |
| `items` | 升序的历史弹幕，可为空数组 |
| `earliestSeq` | 最早仍有历史记录的序号，全部清理时为 `storedThrough + 1` |
| `storedThrough` | 本次快照中连续落库到的最大序号 |
| `targetThrough` | 本次查询的目标上限，最近模式等于 `storedThrough` |
| `nextAfterSeq` | 当前快照可继续读下一页时为本页最后一个序号，否则为 `null`；最近模式为 `null` |

连续补拉时沿用 `throughSeq=targetThrough`。若当前可读数据已经读尽，且游标仍小于目标，用最后已读序号稍后重试。若 `afterSeq < earliestSeq - 1`，返回 `409 ROOM_HISTORY_GAP` 并附 `earliestSeq`、`storedThrough`，客户端重新读取最近窗口。

例如客户端以 `afterSeq=40&throughSeq=42` 请求，数据库只写到 41 时得到以下响应。

```json
{
  "items": [
    {
      "roomSeq": "41",
      "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
      "senderName": "小叶",
      "content": "LiveFlow",
      "sentAt": "2026-11-24T08:30:00.123Z"
    }
  ],
  "earliestSeq": "1",
  "storedThrough": "41",
  "targetThrough": "42",
  "nextAfterSeq": null
}
```

## WebSocket 接收协议

生产使用同源 `wss`，本地使用现有 Vite 代理。验票后等待房间订阅就绪，先排入 `ready` 再允许后续实时帧。连接沿用来源校验、心跳及当前会话复查。 [V1 连接和首帧](../api.md#websocket-接收协议) · [房间订阅](storage.md#房间频道与订阅)

| 帧 | 方向 | 字段与处理 |
| --- | --- | --- |
| `auth` | 客户端 → 服务端 | 首帧，含 `ticket` |
| `ready` | 服务端 → 客户端 | 含 `roomId`，开始初始历史读取或重连补拉 |
| `message` | 服务端 → 客户端 | 附带全部公开弹幕字段 |
| `history_progress` | 服务端 → 客户端 | 含 `storedThrough`。高于本地连续游标时触发合并补拉 |
| `resync_required` | 服务端 → 客户端 | 含 `roomId`，订阅恢复后要求从本地连续游标开始补拉 |
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

## 弹幕数据

| 字段 | 约定 |
| --- | --- |
| `roomSeq` | 房间内独立的正整数序号，供实时和历史合并 |
| `messageId` | Kafka 正式接收时生成的标准小写 UUID v4 |
| `senderName` | 消息中的显示名快照，有昵称时用昵称，缺省时保留原账号拼写 |
| `content` | 服务端规范化的纯文本 |
| `sentAt` | 受理时间，页面用本地时区格式化为 `HH:mm:ss` |

## HTTP 错误

错误正文使用 `code`、`message`。历史范围过期时另带水位字段。

| HTTP | 错误码 | 行为 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 房间、提交标识、正文或查询参数格式无效 |
| `401`、`403` | 沿用认证及 CSRF 错误 | 发送遇到凭证错误时结束本次操作 |
| `404` | `ROOM_NOT_FOUND` | 目标房间不存在 |
| `409` | `MESSAGE_ID_CONFLICT` | 相同提交标识已绑定不同正文 |
| `409` | `ROOM_HISTORY_GAP` | 所需前缀已清理，读取最近窗口 |
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

- 首次 `ready` 后立即缓冲实时事件，读取最近历史，以该页 `storedThrough` 为连续起点；页面将历史中的最近记录展示，再应用更大的连续缓冲项。已有连续游标的重连从该游标续拉。页面刷新按首次进房处理。
- 收到较大序号时保留缺口后的事件，按固定目标补拉。游标只推进到最后一个连续位置。同一序号和消息 ID 的历史与实时记录只展示一次。收到历史水位提高或重新订阅提示时，从当前游标再查询，避免静默漏掉最后一条消息。
- 补拉同一时刻只执行一次，允许缓冲最多 300 条实时事件。达到上限、收到历史范围过期或检测到同一序号映射不同消息时，提示重新同步并从最近窗口重新建立基线。历史服务暂不可用时，保留当前列表和游标，已收到的连续实时项继续展示。 [水位写入](database.md#历史写入和水位)
- 前端只渲染最近 100 条，维持原来的底部跟随、上翻阅读位置、草稿及输入法交互。旧房间、连接或会话的请求返回后由页面代次校验丢弃。
