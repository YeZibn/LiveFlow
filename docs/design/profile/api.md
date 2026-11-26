# 个人资料 HTTP 接口

## 公共约定

调用方为 LiveFlow 同源 Vue 客户端。两条路由都要求 `Authorization: Bearer <accessToken>`。安全过滤链验证 JWT 和 Redis 当前会话，Controller 从可信 JWT 的 `sub` 取得账号 ID。接口只访问本人的资料。成功响应使用 `application/json`，并带有 `Cache-Control: no-store`。 [模块设计](design.md) · [认证公共约定](../auth/api.md#公共约定)

更新请求发送 `Content-Type: application/json`，沿用 认证态 CSRF Cookie 和 `X-XSRF-TOKEN` 请求头。前端请求层应支持 `PUT` 并附加 CSRF 请求头。登录身份的建立和页面恢复继续使用现有 `/auth/me` 流程。 [Cookie 与 CSRF](../auth/api.md#cookie-与-csrf)

## 路由

| 方法与路径 | 请求 | 成功结果 |
| --- | --- | --- |
| `GET /api/v1/users/me/profile` | Bearer，无请求体 | `200`，返回当前账号资料或默认资料 |
| `PUT /api/v1/users/me/profile` | Bearer、CSRF、完整资料 JSON | `200`，返回保存并规范化后的资料 |

## 读取资料

接口无路径参数或查询条件。已有账号尚无资料行时，返回该账号的 `userId` 和 `username`，`nickname` 与 `bio` 为空字符串，`avatarKey` 为 `default`，`displayName` 为 `username`。读取默认资料时数据库保持原状。

## 更新资料

请求体为 JSON 对象，完整提交下表三个字段。字段缺失、未知字段、`null` 和非字符串值均返回 `400 INVALID_REQUEST`。调用方只能修改下列资料字段，包括 `userId`、`username` 在内的额外字段按未知字段拒绝。

| 字段 | 类型 | 规范化与校验 |
| --- | --- | --- |
| `nickname` | string | 去除首尾空白。空字符串表示恢复默认显示名。其他值为 2–24 个 Unicode 码点，支持中文及同名，拒绝换行和控制字符。 |
| `avatarKey` | string | 精确匹配 `default`、`blue`、`green`、`orange` 之一。选择 `default` 恢复默认头像。 |
| `bio` | string | 将 CRLF 和 CR 换行统一转换为 LF，再去除首尾空白。最多 160 个 Unicode 码点，允许空字符串和换行。前端按纯文本保留换行展示。 |

长度按规范化后的 Unicode 码点计算。任一字段校验失败时拒绝整次保存。首次保存与后续更新均返回 `200`。响应在事务提交后发送，内容为本次成功保存的资料。

```json
{
  "nickname": "小叶",
  "avatarKey": "blue",
  "bio": "平时喜欢看直播和写代码。"
}
```

## 成功响应

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `userId` | string | 账号 ID 的十进制字符串，沿用当前用户接口格式 |
| `username` | string | 账号表中原样保存的登录用户名，保留大小写。规则见 [认证请求字段](../auth/api.md#请求字段)。 |
| `nickname` | string | 用户设置的昵称，未设置时为空字符串 |
| `displayName` | string | 使用非空昵称，空昵称时回退到用户名 |
| `avatarKey` | string | 预设头像标识 |
| `bio` | string | 个人简介，未设置时为空字符串 |

```json
{
  "userId": "42",
  "username": "viewer_01",
  "nickname": "小叶",
  "displayName": "小叶",
  "avatarKey": "blue",
  "bio": "平时喜欢看直播和写代码。"
}
```

## 错误、重试与并发

错误采用 `{code, message}`。能够定位到编辑字段的 `400` 可附加 `fieldErrors`，其值为包含字段名与对应提示的对象。无法定位字段时，客户端展示整体错误提示。

| HTTP | 错误码 | 触发条件与客户端处理 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 请求体结构、字段类型或字段规则校验失败。保留输入并提示修改。 |
| `401` | `AUTH_REQUIRED`、`AUTH_ACCESS_INVALID`、`AUTH_ACCESS_EXPIRED`、`AUTH_SESSION_INACTIVE`、`AUTH_SESSION_REPLACED` | 沿用认证错误定义。账号已不存在时按会话失效处理。明确的 Access Token 过期可走一次同会话续期与重发。 |
| `403` | `AUTH_CSRF_INVALID` | 保存请求的 CSRF 凭证缺失或不匹配。保留表单，重新获取 CSRF 后允许用户提交。 |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | 认证依赖不可用。沿用认证模块错误语义。 |
| `503` | `PROFILE_SERVICE_UNAVAILABLE` | 资料读取或保存遇到存储故障。读取可重试。保存保留原输入，结果不明确时先读取确认。 |

```json
{
  "code": "INVALID_REQUEST",
  "message": "请检查请求字段",
  "fieldErrors": {
    "nickname": "昵称应为 2–24 个字符，或留空使用登录用户名"
  }
}
```

相同表单重复提交后资料值相同。不同编辑请求并发时，最后一次成功提交的完整资料生效。网络超时后客户端保留表单，由用户决定重新读取或再次保存。 [事务与并发规则](database.md#读写与事务) · [认证错误约定](../auth/api.md#错误响应)
