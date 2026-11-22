# 登录认证 HTTP 接口

模块范围与主流程见 [登录认证设计](design.md#端到端流程)。凭证格式和会话转换见 [凭证与 Redis 会话](session.md)。

## 公共约定

- 路由前缀为 `/api/v1/auth`。有请求体的接口使用 `application/json` 和 UTF-8。成功响应直接返回业务对象，`204` 响应体为空。
- 用户 ID 在 JSON 中使用十进制字符串，会话 ID 使用 UUID 字符串。接口时间戳使用 UTC ISO 8601，时长使用整数秒。
- 普通受保护接口使用 `Authorization: Bearer <accessToken>`。登录、刷新、退出走各自的认证方式，前端发这三个请求时不附加 Bearer。
- Vue 仅在运行内存中保存 Access Token。Refresh Token 通过 Cookie 传递。登录和刷新响应使用 `Cache-Control: no-store`。

## 路由

| 方法与路径 | 输入与凭证 | 成功响应 | 服务端行为 |
| --- | --- | --- | --- |
| `GET /api/v1/auth/csrf` | 匿名，无请求体 | `204` | 确保浏览器取得 `XSRF-TOKEN` Cookie |
| `POST /api/v1/auth/login` | `LoginRequest`，CSRF | `200`，`TokenResponse`，设置 `lf_refresh` | 校验密码并原子替换 Redis 当前会话 |
| `POST /api/v1/auth/refresh` | `lf_refresh` Cookie，CSRF，无请求体 | `200`，`TokenResponse`，替换 `lf_refresh` | 在当前会话内轮换 Refresh Token，沿用原截止时间 |
| `POST /api/v1/auth/logout` | `LogoutRequest`，`lf_refresh` Cookie，CSRF | `204`，清除 `lf_refresh` | 校验并原子撤销请求指向的当前会话 |
| `GET /api/v1/auth/me` | Bearer Access Token，无请求体 | `200`，`CurrentUserResponse` | 按认证身份中的用户 ID 查询 MySQL |

## 接口详情

### 初始化 CSRF

匿名调用 `GET /api/v1/auth/csrf`。请求体为空。返回 `204`，确保浏览器已取得 `XSRF-TOKEN` Cookie。Vue 在首次进入页面、登录成功和退出成功后调用。

### 登录

`POST /api/v1/auth/login` 携带 CSRF 请求头和以下 JSON。密码验证通过且 Redis 确认会话替换后，返回 `200`、`TokenResponse` 和新的 `lf_refresh` Cookie。账号或密码错误统一返回 `AUTH_INVALID_CREDENTIALS`。

```json
{
  "username": "viewer_01",
  "password": "<用户输入的密码>"
}
```

### 刷新

`POST /api/v1/auth/refresh` 的请求体为空。由浏览器发送 `lf_refresh`，Vue 附加 CSRF 请求头。轮换提交后返回 `200`、`TokenResponse` 和新的 `lf_refresh` Cookie。刷新前后的 `sessionId` 和 `sessionExpiresAt` 相同。凭证失效或命中重放时按 [错误响应](#错误响应) 处理。

### 退出

`POST /api/v1/auth/logout` 携带 `lf_refresh` Cookie 和 CSRF 请求头，JSON 中的 `sessionId` 取自页面正在操作的登录会话。

```json
{
  "sessionId": "4e28b81c-56ae-4f38-b775-fecb71f437b2"
}
```

确认撤销后返回 `204` 并清除 `lf_refresh`。如果 Cookie 中的会话 ID 与请求目标不同，返回 `409 AUTH_SESSION_CHANGED`，保留当前 Cookie 和会话。凭证归属及 Redis 裁决见 [会话退出规则](session.md#原子操作与失败处理)。

### 当前用户

`GET /api/v1/auth/me` 携带 Bearer Access Token，请求体为空。通过鉴权后按登录身份的用户 ID 查询，返回 `200` 和 `CurrentUserResponse`。客户端无需额外提供用户 ID。

## 请求字段

| DTO | 字段 | 类型 | 校验 |
| --- | --- | --- | --- |
| `LoginRequest` | `username` | string | 必填，输入使用 ASCII。转为小写后匹配 `[a-z0-9][a-z0-9_]{2,31}` |
| `LoginRequest` | `password` | string | 必填且非空，最多 256 个 UTF-8 字节，按原值验证 |
| `LogoutRequest` | `sessionId` | string | 必填，UUID。指明页面正在操作的会话，凭证仍由 Cookie 提供 |

## 响应字段

| DTO | 字段 | 类型 | 含义 |
| --- | --- | --- | --- |
| `TokenResponse` | `accessToken` | string | 签发的 JWT |
| `TokenResponse` | `tokenType` | string | 固定为 `Bearer` |
| `TokenResponse` | `expiresIn` | integer | Access Token 有效秒数，最多 900，并受会话截止时间限制 |
| `TokenResponse` | `sessionId` | string | 本次登录的会话 UUID。刷新前后相同 |
| `TokenResponse` | `sessionExpiresAt` | string | 本次登录的绝对截止时间。刷新前后相同 |
| `CurrentUserResponse` | `userId` | string | MySQL 用户 ID 的十进制表示 |
| `CurrentUserResponse` | `username` | string | 规范化后的用户名 |

`TokenResponse` 示例

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "sessionId": "4e28b81c-56ae-4f38-b775-fecb71f437b2",
  "sessionExpiresAt": "2026-11-29T02:00:00Z"
}
```

`CurrentUserResponse` 示例

```json
{
  "userId": "42",
  "username": "viewer_01"
}
```

## Cookie 与 CSRF

| Cookie | 配置及用法 |
| --- | --- |
| `lf_refresh` | `HttpOnly`、`Secure`、`SameSite=Lax`、`Path=/api/v1/auth`，省略 `Domain`。`Max-Age` 为会话剩余秒数。退出成功使用相同名称、范围和 `Max-Age=0` 清除。正式环境使用 HTTPS，本地回环 HTTP 开发环境可显式覆盖 `Secure`。 |
| `XSRF-TOKEN` | 由 Spring Security 为同源 SPA 提供，Vue 读取后通过 `X-XSRF-TOKEN` 回传。它用于 CSRF 校验。 |

页面初始化时获取 CSRF Cookie，再执行登录或尝试续期。登录或退出成功后，前端再次调用 CSRF 接口，为后续 POST 获取有效的 CSRF Cookie。三个 POST 均验证 CSRF。Cookie 中的会话 ID 和退出请求的目标 ID 不一致时，响应 `409`，保留浏览器当前 Cookie。 [Spring Security SPA CSRF](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/csrf.html#csrf-integration-javascript-spa) · [HTTP Cookie 规范](https://www.rfc-editor.org/rfc/rfc6265.html)

## 错误响应

```json
{
  "code": "AUTH_SESSION_REPLACED",
  "message": "当前账号已在其他会话登录"
}
```

`code` 为稳定的客户端分支依据，`message` 用于提示。错误响应不暴露凭证值、摘要或存储异常细节。

| HTTP | 错误码 | 触发条件与客户端处理 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 请求体或字段格式错误，提示检查输入 |
| `401` | `AUTH_INVALID_CREDENTIALS` | 账号不存在或密码不匹配，统一提示账号或密码错误 |
| `401` | `AUTH_REQUIRED` | 缺少接口要求的 Bearer 凭证或 Refresh Cookie |
| `401` | `AUTH_ACCESS_INVALID` | JWT 格式、签名或其他校验失败，清理对应本地会话 |
| `401` | `AUTH_ACCESS_EXPIRED` | JWT 已到期，发起一次受跨标签页锁保护的刷新 |
| `401` | `AUTH_SESSION_INACTIVE` | 通过 JWT 校验并定位账号后发现没有有效会话，清理对应本地状态 |
| `401` | `AUTH_SESSION_REPLACED` | 通过 JWT 校验后发现该账号当前会话的 ID 不同，清理对应本地状态 |
| `401` | `AUTH_REFRESH_INVALID` | Refresh Token 格式错误、摘要未知、会话过期或已替换，提示重新登录 |
| `401` | `AUTH_REFRESH_REUSED` | 刷新命中当前会话的已消费摘要，该会话已撤销，提示重新登录 |
| `403` | `AUTH_CSRF_INVALID` | CSRF Cookie 或请求头缺失或不匹配，重新获取 CSRF 凭证 |
| `409` | `AUTH_SESSION_CHANGED` | 退出请求目标与浏览器 Refresh Cookie 所属会话不同，保留新会话 |
| `429` | `AUTH_RATE_LIMITED` | 触发限流，提示稍后再试 |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | Redis 等认证依赖不可用或写入结果无法确认。刷新结果不明时重新登录 |
