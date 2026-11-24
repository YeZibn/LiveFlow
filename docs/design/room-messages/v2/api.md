# 直播间弹幕 V2 接口设计

## 公共约定

HTTP 沿用同源、Bearer 鉴权与账号当前会话校验，POST 继续校验 CSRF。目标房间使用标准小写 UUID v4。服务端从安全上下文获取账号和会话。成功与错误响应携带 `Cache-Control: no-store`。弹幕时间为 UTC ISO 8601，固定三位小数秒；正整数序号使用规范十进制字符串，初始游标和水位可以为 `"0"`。Vue 用整数比较语义处理序号。 [房间要求](../../room/api.md#查询房间详情) · [鉴权约定](../../auth/api.md#公共约定)

| 方法和路径 | 用途 | 成功结果 |
| --- | --- | --- |
| `POST /api/v1/rooms/{roomId}/ws-ticket` | 为当前会话签发一次性连接 Ticket | `200`，`ticket` 与 `expiresIn` |
| `POST /api/v1/rooms/{roomId}/messages` | 提交弹幕或重试同一提交 | `202`，提交已获得 Kafka 写入确认 |
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

`202 Accepted` 带 `Location` 响应头，指向同房间下的提交回执。响应中的 `submissionId` 是本次预约的查询标识，`retryUntil` 是首次获得许可后 10 分钟，`receiptExpiresAt` 是首次许可后 30 分钟。客户端应使用同一个 `clientMessageId` 和正文重试原提交，并保持原来的客户端重试期限。已经确认入队的同一预约返回原 `202` 信息。 [Kafka 写入确认](event-contract.md#生产和确认) · [HTTP 202 语义](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3)

```json
{
  "submissionId": "4154ce3d-d846-4bd5-8fa9-a79b156a11ed",
  "status": "queued",
  "retryUntil": "2026-11-24T08:40:00.000Z",
  "receiptExpiresAt": "2026-11-24T09:00:00.000Z"
}
```

收到 `429 MESSAGE_RATE_LIMITED` 时，页面保留草稿并按 `Retry-After` 显示等待时间，倒计时期间禁用新发送。原标识重试和回执查询可继续使用。 [账号冷却及 API 规则](../../resilience/api.md)

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

前端获得 `202` 后可轮询回执，初始间隔为 500 毫秒，未确认时退避并限制同一提交只保留一个请求。连续尝试 10 秒仍未确认时，显示「待确认」并提供手动查询。到达回执截止、离开房间或会话切换时停止轮询。相同提交重试应保留刚才的原始正文和标识，明确发送新内容时生成新标识。

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

## 错误和重试

错误正文使用 `code`、`message`。历史范围过期时另带水位字段。

| HTTP | 错误码 | 行为 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 房间、提交标识、正文或查询参数格式无效 |
| `401`、`403` | 沿用认证及 CSRF 错误 | 明确的 Access Token 过期继续沿用同会话续期 |
| `404` | `ROOM_NOT_FOUND` | 目标房间不存在 |
| `409` | `MESSAGE_ID_CONFLICT` | 相同提交标识已绑定不同正文 |
| `409` | `MESSAGE_SUBMISSION_EXPIRED` | 仍待确认的已知预约超出 10 分钟重试窗口，仍可在回执查询期内查询 |
| `409` | `ROOM_HISTORY_GAP` | 所需前缀已清理，读取最近窗口 |
| `429` | `MESSAGE_RATE_LIMITED` | 额度用尽，返回整数秒 `Retry-After` |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | 当前会话无法确认 |
| `503` | `ROOM_MESSAGES_UNAVAILABLE` | 依赖故障或入口过载，本次调用在尝试生产前停止；回执和历史读取暂不可用也使用此码 |
| `503` | `MESSAGE_SUBMISSION_UNKNOWN` | 已开始生产但得不到可靠结果，或同一提交仍有在途租约。保留原标识与正文，按需附 `Retry-After` |

HTTP 超时或网关响应来源不明确时按结果未知处理。收到 `202` 后，正式流程发现冲突或过期时通过终态回执报告。重连只恢复接收和历史合并，发送重试由用户触发。 [提交租约](storage.md#账号额度和提交绑定)

## 前端合并与恢复

- 首次 `ready` 后立即缓冲实时事件，读取最近历史，以该页 `storedThrough` 为连续起点；页面将历史中的最近记录展示，再应用更大的连续缓冲项。已有连续游标的重连从该游标续拉。页面刷新按首次进房处理。
- 收到较大序号时保留缺口后的事件，按固定目标补拉。游标只推进到最后一个连续位置。同一序号和消息 ID 的历史与实时记录只展示一次。收到历史水位提高或重新订阅提示时，从当前游标再查询，避免静默漏掉最后一条消息。
- 补拉同一时刻只执行一次，允许缓冲最多 300 条实时事件。达到上限、收到历史范围过期或检测到同一序号映射不同消息时，提示重新同步并从最近窗口重新建立基线。历史服务暂不可用时，保留当前列表和游标，已收到的连续实时项继续展示。 [水位写入](database.md#历史写入和水位)
- 前端只渲染最近 100 条，维持原来的底部跟随、上翻阅读位置、草稿及输入法交互。旧房间、连接或会话的请求返回后由页面代次校验丢弃。
