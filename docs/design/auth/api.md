# 账号注册与登录认证 HTTP 接口

模块范围与主流程见 [登录认证设计](design.md#端到端流程)。凭证格式和会话转换见 [凭证与 Redis 会话](session.md)。

## 公共约定

- 路由前缀为 `/api/v1/auth`。有请求体的接口使用 `application/json` 和 UTF-8。成功响应直接返回业务对象，`204` 响应体为空。
- 用户 ID 在 JSON 中使用十进制字符串，会话 ID 使用 UUID 字符串。接口时间戳使用 UTC ISO 8601，时长使用整数秒。
- 普通受保护接口使用 `Authorization: Bearer <accessToken>`。注册、登录、刷新和退出使用各自约定的凭证，由浏览器携带 CSRF Cookie 并提交 CSRF 请求头。
- Vue 仅在运行内存中保存 Access Token。Refresh Token 通过 Cookie 传递。注册、登录和刷新响应使用 `Cache-Control: no-store`。

## 路由

| 方法与路径 | 输入与凭证 | 成功响应 | 服务端行为 |
| --- | --- | --- | --- |
| `GET /api/v1/auth/csrf?scope=pre-auth` | 匿名，无请求体 | `204` | 初始化或复用匿名预会话与匿名 CSRF Cookie |
| `GET /api/v1/auth/csrf?scope=session` | Bearer 或当前 Refresh Cookie，无请求体 | `204` | 为已确认的当前会话补领认证态 CSRF Cookie |
| `POST /api/v1/auth/register` | 匿名，`RegisterRequest`，CSRF | `201`，`CurrentUserResponse` | 创建账号并返回原样账号名 |
| `POST /api/v1/auth/login` | `LoginRequest`，CSRF | `200`，`TokenResponse`，设置 `lf_refresh` | 校验密码并原子替换 Redis 当前会话 |
| `POST /api/v1/auth/refresh` | `lf_refresh` Cookie，CSRF，无请求体 | `200`，`TokenResponse`，替换 `lf_refresh` | 在当前会话内轮换 Refresh Token，沿用原截止时间 |
| `POST /api/v1/auth/logout` | `LogoutRequest`，`lf_refresh` Cookie，CSRF | `204`，清除 `lf_refresh` | 校验并原子撤销请求指向的当前会话 |
| `GET /api/v1/auth/me` | Bearer Access Token，无请求体 | `200`，`CurrentUserResponse` | 按认证身份中的用户 ID 查询 MySQL |

## 接口详情

### 初始化 CSRF

`scope` 必填，且只能出现一次。两个用途均返回不可缓存的 `204`。登录前用 `pre-auth` 初始化。恢复登录时如已持有认证态 CSRF Cookie，直接刷新，缺失时用 `session` 补领。补领只读验证调用身份，不变更会话。 [签发与校验顺序](security.md#http-接口与校验顺序)

### 注册

`POST /api/v1/auth/register` 携带 `X-XSRF-TOKEN` 请求头。请求体包含 `username` 和 `password` 两个字符串。字段缺失、值为 `null`、字段类型错误或出现额外字段时返回 `400 INVALID_REQUEST`。密码确认和显示切换由前端表单处理，服务端按下方规则校验提交的密码。

```json
{
  "username": "Alice",
  "password": "LiveFlow!26"
}
```

注册按可信来源 IP 使用独立的 Redis 固定窗口，初始配置为 10 分钟最多接受 10 次格式有效的提交。限流在计算密码哈希前执行，超限返回 `429 AUTH_RATE_LIMITED` 和整数秒 `Retry-After`。

账号写入提交后，返回 `201 Created`、`Cache-Control: no-store` 和 `CurrentUserResponse`。前端清空密码，切回登录页并预填原样账号名。重复注册的响应见 [错误响应](#错误响应)。

### 登录

`POST /api/v1/auth/login` 携带 CSRF 请求头和以下 JSON。账号查询及账号维度限流按 `username` 的原始拼写执行，`Alice` 和 `alice` 分别匹配。密码验证通过且 Redis 确认会话替换后，返回 `200`、`TokenResponse` 和新的 `lf_refresh` Cookie。账号或密码错误统一返回 `AUTH_INVALID_CREDENTIALS`。账号与来源 IP 共同裁决登录额度，任一超限即返回整数秒 `Retry-After`。 [限流接口](../resilience/api.md#超限响应)

```json
{
  "username": "Alice",
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
| `RegisterRequest`、`LoginRequest` | `username` | string | 必填，原样匹配 `[A-Za-z0-9][A-Za-z0-9_]{2,31}`，严格区分大小写 |
| `RegisterRequest` | `password` | string | 必填，10–25 个可见 ASCII 字符，至少包含一个大写字母、小写字母、数字和符号，按原值写入密码哈希流程 |
| `LoginRequest` | `password` | string | 必填且非空，最多 256 个 UTF-8 字节，按原值验证 |
| `LogoutRequest` | `sessionId` | string | 必填，UUID。指明页面正在操作的会话，凭证仍由 Cookie 提供 |

注册密码允许的字符范围为 ASCII `0x21–0x7E`。符号指此范围内除英文字母和数字以外的标点字符，包括 `!`、`@`、`#` 和 `_`。客户端与服务端按相同字符范围校验。

## 响应字段

| DTO | 字段 | 类型 | 含义 |
| --- | --- | --- | --- |
| `TokenResponse` | `accessToken` | string | 签发的 JWT |
| `TokenResponse` | `tokenType` | string | 固定为 `Bearer` |
| `TokenResponse` | `expiresIn` | integer | Access Token 有效秒数，最多 900，并受会话截止时间限制 |
| `TokenResponse` | `sessionId` | string | 本次登录的会话 UUID。刷新前后相同 |
| `TokenResponse` | `sessionExpiresAt` | string | 本次登录的绝对截止时间。刷新前后相同 |
| `CurrentUserResponse` | `userId` | string | MySQL 用户 ID 的十进制表示 |
| `CurrentUserResponse` | `username` | string | 账号保存的原始用户名，保留大小写 |

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

`CurrentUserResponse` 示例，注册成功和本人查询使用相同结构。

```json
{
  "userId": "42",
  "username": "Alice"
}
```

## Cookie 与 CSRF

Cookie 的用途及签名边界见 [CSRF 安全设计](security.md#http-接口与校验顺序)。

| Cookie | 配置及用法 |
| --- | --- |
| `lf_refresh` | `HttpOnly`、`Secure`、`SameSite=Lax`、`Path=/api/v1/auth`，省略 `Domain`。`Max-Age` 为会话剩余秒数。退出成功使用相同名称、范围和 `Max-Age=0` 清除。正式环境使用 HTTPS，本地回环 HTTP 开发环境可显式覆盖 `Secure`。 |
| `__Host-lf_pre` | 短期匿名预会话，`HttpOnly`。 |
| `__Host-lf_csrf_pre` | Vue 可读，绑定匿名预会话。用于注册与登录。 |
| `__Host-lf_csrf` | Vue 可读，绑定已登录的 `sessionId`。用于业务写请求、刷新和退出。 |

三种 CSRF 相关 Cookie 在生产均使用 `Secure`、`Path=/`、`SameSite=Lax`，省略 `Domain`。显式启用的 `local` 回环 HTTP 使用去掉 `__Host-` 的名称。Vue 将当前操作对应的 CSRF Cookie 值通过 `X-XSRF-TOKEN` 回传。登录成功后服务端随响应签发认证态 CSRF 并清除匿名 Cookie。退出确认后清除 Refresh 和认证态 CSRF。认证态 CSRF 校验通过后，若 Refresh Cookie 指向的会话与退出目标不同，返回 `409` 并保留浏览器当前 Cookie。

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
| `403` | `AUTH_CSRF_INVALID` | 来源、Cookie、请求头、签名或会话绑定校验失败。补领合适的 CSRF 凭证后，由调用方决定是否重新提交 |
| `409` | `AUTH_USERNAME_ALREADY_EXISTS` | 注册时发现完全相同的账号名，提示更换账号名或登录 |
| `409` | `AUTH_SESSION_CHANGED` | 退出请求目标与浏览器 Refresh Cookie 所属会话不同，保留新会话 |
| `429` | `AUTH_RATE_LIMITED` | 触发限流，登录和注册按 `Retry-After` 提示等待时间 |
| `503` | `AUTH_SERVICE_UNAVAILABLE` | Redis 或 MySQL 等依赖不可用或写入结果无法确认。注册结果不明时可尝试登录，刷新结果不明时重新登录 |
