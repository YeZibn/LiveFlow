# 直播间 HTTP 接口

## 公共约定

调用方为 LiveFlow 同源 Vue 客户端。四个接口均要求 `Authorization: Bearer <accessToken>`。后端从已通过签名与 Redis 当前会话校验的 JWT `sub` 取得账号 ID。创建和本人查询按账号归属操作。统一列表和房间详情向所有有效登录会话开放。成功响应为 `application/json`，携带 `Cache-Control: no-store`。 [直播间设计](design.md) · [认证公共约定](../auth/api.md#公共约定)

创建请求发送 `Content-Type: application/json`，沿用 认证态 CSRF Cookie 和 `X-XSRF-TOKEN` 请求头。查询请求无请求体。 [Cookie 与 CSRF](../auth/api.md#cookie-与-csrf)

## 路由

| 方法与路径 | 用途 | 成功状态 |
| --- | --- | --- |
| `POST /api/v1/rooms` | 为当前账号创建房间 | `201 Created` |
| `GET /api/v1/users/me/room` | 查询当前账号的房间 | `200 OK` |
| `GET /api/v1/rooms` | 浏览与搜索统一直播间列表 | `200 OK` |
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

启用房间存在性缓存后，服务端在提交后为已创建房间预热。预热失败时，已提交的创建仍返回 `201`。若客户端需要确认请求结果，继续读取本人房间。 [提交后预热](storage.md#创建预热和可见性)

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

## 浏览与搜索直播间

`GET /api/v1/rooms` 返回按房间创建时间倒序排列的已创建房间，包含请求账号自己的房间。多个房间在同一毫秒创建时，按房间 UUID 倒序确定相对位置。 [列表查询与索引](database.md#列表查询与分页)

| 参数 | 类型 | 默认与约束 |
| --- | --- | --- |
| `q` | string | 可选。沿用 [房间名称](#创建房间) 的字符检查和首尾空白清理规则。规范化后允许 0–40 个 Unicode 码点，空关键词浏览全部。 |
| `limit` | integer | 可选，默认 12，允许 1–24。 |
| `cursor` | string | 可选。省略时读取第一页，翻页时使用同一关键词对应响应中的 `nextCursor`。 |

关键词按标题中的连续文字匹配，保留内部空白，支持单个汉字。英文匹配忽略大小写，`%`、`_` 和 `!` 按输入的普通字符匹配。搜索结果沿用同一创建时间排序。重复或未知的查询参数，以及不符合约束的关键词、页大小、游标均返回 `400 INVALID_REQUEST`。

例如请求 `GET /api/v1/rooms?q=%E8%81%8A%E8%81%8A&limit=12`，成功返回列表和下一页游标。下面的示例表示结果已读完。

```json
{
  "items": [
    {
      "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
      "title": "周末聊聊",
      "ownerName": "小叶",
      "createdAt": "2026-11-23T08:30:00.123Z",
      "isMine": false
    }
  ],
  "nextCursor": null
}
```

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `items` | array | 当页房间。没有结果时为 `[]`。 |
| `items[].roomId`、`title`、`createdAt` | string | 见 [房间对象](#房间对象)。 |
| `items[].ownerName` | string | 房主的显示名，沿用 [详情显示名规则](#查询房间详情)。 |
| `items[].isMine` | boolean | 由服务端比较房主与当前认证账号得出。 |
| `nextCursor` | string 或 null | 不透明的下一页令牌。此值为 `null` 时停止分页。 |

## 查询房间详情

`GET /api/v1/rooms/{roomId}` 要求有效登录会话。路径参数严格匹配标准小写 UUID v4 格式 `[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}`。无需请求体和查询参数。

通过鉴权后按 UUID 查询。格式无效返回 `400 INVALID_REQUEST`。格式有效且无对应房间返回 `404 ROOM_NOT_FOUND`。查到房间返回 `200 OK`，正文包含 `roomId`、`title` 和 `ownerName`。读取详情不产生写入。

`ownerName` 在读取时以当前房主资料生成。有昵称时展示昵称，未设置时使用保留原拼写的登录用户名。该字段对具有房间访问权限的用户可见。 [个人资料显示名](../profile/api.md#成功响应)

```json
{
  "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
  "title": "周末聊聊",
  "ownerName": "小叶"
}
```

## 房间对象

| 字段 | 类型 | 格式与含义 |
| --- | --- | --- |
| `roomId` | string | 稳定的标准小写 UUID v4。创建、本人查询、列表和详情使用同一标识。 |
| `title` | string | 已规范化并保存的房间名称。 |
| `createdAt` | string | 用于创建、本人查询和列表。ISO 8601 UTC 时间，固定三位小数秒并以 `Z` 结尾。 |

## 错误与重试

错误采用 `{code, message}`。字段校验错误可附加 `fieldErrors.title`。客户端按 `code` 分支。 [认证错误契约](../auth/api.md#错误响应)

| HTTP | 错误码 | 触发条件与客户端处理 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 创建请求结构或名称无效。保留输入并提示修正。 |
| `400` | `INVALID_REQUEST` | 列表查询参数无效或游标与关键词不匹配。保留搜索输入并从第一页重新查询。 |
| `400` | `INVALID_REQUEST` | 详情请求的房间 ID 格式无效。显示「房间链接无效」。 |
| `401` | 沿用认证错误码 | Bearer 缺失、无效、已过期或会话失效。账号已不存在时返回 `AUTH_SESSION_INACTIVE`。明确的 `AUTH_ACCESS_EXPIRED` 可在同会话续期后重发一次原请求。 |
| `403` | `AUTH_CSRF_INVALID` | 创建请求未通过 CSRF 校验。保留表单，重新获取 CSRF 后允许用户提交。 |
| `404` | `ROOM_NOT_FOUND` | 合法的 UUID 没有对应房间。显示「房间不存在」，提供返回直播间列表入口。 |
| `409` | `ROOM_ALREADY_EXISTS` | 当前账号已有房间。重新读取本人房间，显示已存在的记录。 |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | 认证依赖故障。沿用认证模块处理。 |
| `503` | `ROOM_SERVICE_UNAVAILABLE` | 房间读取或写入故障。读取可重试。创建结果可能不明确时先查询本人房间。 |

创建请求超时、网络中断或响应无法确认时，客户端保留原输入并先查询。若查询显示房间已经存在，展示该房间。若查询明确返回空，允许用户手动再次提交。重复请求不会改写房间。 [并发与事务规则](database.md#读写与事务)
