# 直播间 HTTP 接口

## 公共约定

调用方为 LiveFlow 同源 Vue 客户端。三个接口均要求 `Authorization: Bearer <accessToken>`。后端从已通过签名与 Redis 当前会话校验的 JWT `sub` 取得账号 ID。创建和本人查询按账号归属操作；详情查询对所有有效登录会话开放，按路径中的房间 UUID 定位。成功响应为 `application/json`，携带 `Cache-Control: no-store`。 [直播间设计](design.md) · [认证公共约定](../auth/api.md#公共约定)

创建请求发送 `Content-Type: application/json`，沿用 `XSRF-TOKEN` Cookie 和 `X-XSRF-TOKEN` 请求头。查询请求无请求体。 [Cookie 与 CSRF](../auth/api.md#cookie-与-csrf)

## 路由

| 方法与路径 | 用途 | 成功状态 |
| --- | --- | --- |
| `POST /api/v1/rooms` | 为当前账号创建房间 | `201 Created` |
| `GET /api/v1/users/me/room` | 查询当前账号的房间 | `200 OK` |
| `GET /api/v1/rooms/{roomId}` | 查询房间页面所需信息 | `200 OK` |

## 创建房间

请求体为单个 JSON 对象，只接受一个 `title` 字段。字段缺失、重复键、未知字段（包括 `ownerUserId`）、`null`、非字符串值或无法解析的 JSON 返回 `400 INVALID_REQUEST`。

| 字段 | 类型 | 校验 |
| --- | --- | --- |
| `title` | string | 必填。拒绝换行符和控制字符，去除首尾空白后按 Unicode 码点计数，须为 1–40 个。允许同名，保留字符大小写及内部空白。 |

名称校验先检查原始输入中的 C0 和 C1 控制字符（含制表符、CR 和 LF）、Unicode 行、段分隔符（U+2028、U+2029）及未配对的代理项，再按 Unicode `White_Space` 去除首尾空白。前端可按同一规则预校验，以服务端校验结果为准。前端以纯文本显示名称。

请求示例

```json
{
  "title": "周末聊聊"
}
```

事务成功提交后返回 `201 Created`，响应体为下列房间对象。账号已拥有房间时返回 `409 ROOM_ALREADY_EXISTS`，原房间数据保留。

```json
{
  "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
  "title": "周末聊聊",
  "createdAt": "2026-11-23T08:30:00.123Z"
}
```

## 查询本人房间

接口返回 `200 OK`。账号有房间时，`room` 为房间对象。该查询按当前账号归属读取，且无写入副作用。

```json
{
  "room": {
    "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
    "title": "周末聊聊",
    "createdAt": "2026-11-23T08:30:00.123Z"
  }
}
```

当前账号有效且还没有房间时，同样返回 `200 OK`，包含显式的空值。

```json
{
  "room": null
}
```

## 查询房间详情

`GET /api/v1/rooms/{roomId}` 要求有效登录会话。路径参数严格匹配标准小写 UUID v4 格式 `[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}`。无需请求体和查询参数。

通过鉴权后按 UUID 查询。格式无效返回 `400 INVALID_REQUEST`。格式有效且无对应房间返回 `404 ROOM_NOT_FOUND`。查到房间返回 `200 OK`，正文只包含 `roomId` 和 `title`。读取详情不产生写入。

```json
{
  "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
  "title": "周末聊聊"
}
```

## 房间对象

| 字段 | 类型 | 格式与含义 |
| --- | --- | --- |
| `roomId` | string | 稳定的标准小写 UUID v4。创建、本人查询和详情查询使用同一标识。 |
| `title` | string | 已规范化并保存的房间名称。 |
| `createdAt` | string | 仅用于创建与本人查询。ISO 8601 UTC 时间，固定三位小数秒并以 `Z` 结尾。 |

## 错误与重试

错误采用 `{code, message}`。字段校验错误可附加 `fieldErrors.title`。客户端按 `code` 分支。 [认证错误契约](../auth/api.md#错误响应)

| HTTP | 错误码 | 触发条件与客户端处理 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 创建请求结构或名称无效。保留输入并提示修正。 |
| `400` | `INVALID_REQUEST` | 详情请求的房间 ID 格式无效。显示「房间链接无效」。 |
| `401` | 沿用认证错误码 | Bearer 缺失、无效、已过期或会话失效。账号已不存在时返回 `AUTH_SESSION_INACTIVE`。明确的 `AUTH_ACCESS_EXPIRED` 可在同会话续期后重发一次原请求。 |
| `403` | `AUTH_CSRF_INVALID` | 创建请求未通过 CSRF 校验。保留表单，重新获取 CSRF 后允许用户提交。 |
| `404` | `ROOM_NOT_FOUND` | 合法的 UUID 没有对应房间。显示「房间不存在」，提供返回个人主页入口。 |
| `409` | `ROOM_ALREADY_EXISTS` | 当前账号已有房间。重新读取本人房间，显示已存在的记录。 |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | 认证依赖故障。沿用认证模块处理。 |
| `503` | `ROOM_SERVICE_UNAVAILABLE` | 房间读取或写入故障。读取可重试。创建结果可能不明确时先查询本人房间。 |

创建请求超时、网络中断或响应无法确认时，客户端保留原输入并先查询。若查询显示房间已经存在，展示该房间。若查询明确返回空，允许用户手动再次提交。重复请求不会改写房间。 [并发与事务规则](database.md#读写与事务)
