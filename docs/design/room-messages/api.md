# 直播间弹幕接口

## 公共约定

两个 POST 都要求 `Authorization: Bearer <accessToken>`、认证态 CSRF Cookie 及 `X-XSRF-TOKEN` 请求头。后端从已验证的 JWT 取得账号和登录会话，按房间 UUID 定位房间。有效登录用户可向存在的房间发送并申请接收连接。成功响应为 JSON，携带 `Cache-Control: no-store`。房间路径参数沿用详情接口的标准小写 UUID v4。 [弹幕设计](design.md) · [房间 UUID 约定](../room/api.md#查询房间详情) · [Cookie 与 CSRF](../auth/api.md#cookie-与-csrf)

| 方法与路径 | 请求 | 成功结果 |
| --- | --- | --- |
| `POST /api/v1/rooms/{roomId}/ws-ticket` | 无正文、无查询参数 | `200 OK`，Ticket 和有效秒数 |
| `POST /api/v1/rooms/{roomId}/messages` | JSON 正文、无查询参数 | `200 OK`，服务端生成的消息标识和时间 |
| `/ws/rooms` | WebSocket 升级，首帧提交 Ticket | `ready` 确认已绑定房间 |

## 申请接收票据

Ticket 是仅供建立一次连接的不透明随机值。成功响应的 `expiresIn` 表示签发时的有效秒数。客户端在内存中使用 Ticket，通过首帧传输。服务端持有的绑定信息见 [Redis Ticket](storage.md#redis-ticket)。

```json
{
  "ticket": "c2FtcGxlX3RpY2tldF9zdHJpbmdfMzJfYnl0ZXNfMDE",
  "expiresIn": 30
}
```

## 发送弹幕

请求是单个 JSON 对象，只允许下表两个字段。字段缺失、重复键、未知字段、空值、无效 JSON 或类型错误均返回 `400 INVALID_REQUEST`。

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `clientMessageId` | string | 必填。标准小写 UUID v4，按一次用户提交生成。重试原提交时复用。 |
| `content` | string | 必填。先拒绝原始输入中的 C0、C1 控制字符、Unicode 行和段分隔符及未配对代理项，再按 Unicode `White_Space` 去除首尾空白。规范化后须为 1–100 个 Unicode 码点，保留内部空白。 |

```json
{
  "clientMessageId": "1c3d6dfa-58a1-42a3-9141-478c35dad5bf",
  "content": "大家好！"
}
```

`200 OK` 表示新提交已被服务端接受并完成本机分发入队，或相同提交已被接受且服务端返回原结果。未就绪或发送队列不可用的连接可能收不到该消息。返回正文无弹幕列表数据。

| 字段 | 类型 | 格式 |
| --- | --- | --- |
| `messageId` | string | 服务端生成的标准小写 UUID v4。 |
| `sentAt` | string | 服务端受理时的 ISO 8601 UTC 时间，固定三位小数秒，以 `Z` 结尾。 |

```json
{
  "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
  "sentAt": "2026-11-23T08:30:00.123Z"
}
```

## HTTP 错误与重试

错误正文沿用 `{code, message}`。客户端使用 `code` 判定行为。 [认证错误契约](../auth/api.md#错误响应)

| 状态 | 错误码 | 行为 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 路径 UUID、正文结构或发送字段无效。提示修改输入。 |
| `401`、`403` | 沿用认证和 CSRF 错误码 | 明确的 Access Token 过期沿用同会话续期并重发一次。会话失效后停止连接和发送。CSRF 失败时重新获取凭证，由用户重新提交。 |
| `404` | `ROOM_NOT_FOUND` | 目标房间不存在。结束本次操作，并提供返回入口。 |
| `409` | `MESSAGE_ID_CONFLICT` | 同一去重键已接受过不同规范化正文。新正文需使用新 ID。 |
| `429` | `MESSAGE_RATE_LIMITED` | 超过账号发送额度。响应头 `Retry-After` 为允许重试前的整数秒数。保留正文。 |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | 当前会话无法确认。按认证契约处理。 |
| `503` | `ROOM_MESSAGES_UNAVAILABLE` | 房间或发送者资料读取、Ticket 存储或服务端接收能力暂不可用。应用以此错误拒绝的新发送请求不会进入广播。 |

HTTP 超时、传输中断或无法确认来源的网关错误视为结果不确定。用户主动重试同一提交时继续使用原 ID 与输入快照。去重有效范围见 [短期状态](storage.md#jvm-状态与生命周期)。

## WebSocket 接收协议

生产使用同源 `wss`，本地通过现有 Vite `/ws` 代理。握手要求允许的 `Origin`。连接建立后先处于待鉴权状态，只有第一条文本应用帧可以提交 `auth`。默认等待 5 秒。连接鉴权成功后服务端先发 `ready`，随后发送 `message`。客户端应用帧不负责发送弹幕。协议级心跳与应用帧分开处理。

帧为 JSON 对象，`type` 固定为下表值。首帧格式无效、票据不可用、会话失效或依赖故障时，若连接仍可写，服务端可先发送 `error` 再关闭。客户端以关闭事件更新连接状态，无需等待错误帧。

| `type` | 方向 | 字段 |
| --- | --- | --- |
| `auth` | 客户端 → 服务端 | `ticket`，申请接口返回的不透明字符串。 |
| `ready` | 服务端 → 客户端 | `roomId`，该连接绑定的房间 UUID。 |
| `message` | 服务端 → 客户端 | `messageId`、`senderName`、`content`、`sentAt`，所有连接收到同一条消息的数据。 |
| `error` | 服务端 → 客户端 | `code`、`message`，例如 `INVALID_REQUEST`、`TICKET_INVALID` 或认证相关错误码。 |

```json
{"type":"auth","ticket":"c2FtcGxlX3RpY2tldF9zdHJpbmdfMzJfYnl0ZXNfMDE"}
```

```json
{"type":"ready","roomId":"7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c"}
```

```json
{
  "type": "message",
  "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
  "senderName": "小叶",
  "content": "大家好！",
  "sentAt": "2026-11-23T08:30:00.123Z"
}
```

```json
{"type":"error","code":"TICKET_INVALID","message":"连接票据无效或已过期"}
```

### 弹幕广播字段

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `messageId` | string | 本次受理生成的标准小写 UUID v4，与 HTTP 回执相同。 |
| `senderName` | string | 必填，发送者显示名。由服务端使用认证账号读取昵称，未设置时用保留原拼写的登录用户名。每条消息保存受理时使用的显示名。 |
| `content` | string | 服务端规范化后的纯文本正文。 |
| `sentAt` | string | 与 HTTP 回执相同的 UTC 时间，含三位小数秒。Vue 按本地时间渲染为 `HH:mm:ss`。 |

发送请求仍由 `clientMessageId` 与 `content` 组成，HTTP 回执提供 `messageId` 与 `sentAt`。接收页所需的发送者信息由广播中的 `senderName` 提供。 [显示名规则](../profile/api.md#成功响应)
