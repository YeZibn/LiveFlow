# user-auth Specification

## Purpose

为 LiveFlow 的 Vue 客户端建立统一的已有账号认证能力，覆盖密码登录、页面恢复、受保护的 HTTP 请求、凭证轮换和退出，并确保换设备登录或会话撤销后可以按会话边界拒绝旧请求。

## Requirements

### Requirement: 认证接口与浏览器凭证

系统 MUST 按 `docs/design/auth/api.md` 的认证接口契约提供 `GET /api/v1/auth/csrf`、`POST /api/v1/auth/login`、`POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout` 和 `GET /api/v1/auth/me`。三个 POST MUST 校验 CSRF。登录和刷新成功 MUST 通过 JSON 返回 Access Token，通过 `HttpOnly` Refresh Cookie 传递刷新凭证，并标记响应不可缓存。生产 Cookie MUST 使用 `Secure`。

#### Scenario: 初始化与登录凭证交付

- **WHEN** 浏览器匿名获取 CSRF Cookie，再携带有效 CSRF 请求头和正确账号密码登录
- **THEN** CSRF 初始化返回 `204`，登录返回 `200`、`TokenResponse` 和 `lf_refresh` Cookie

#### Scenario: 请求缺少有效 CSRF

- **WHEN** 登录、刷新或退出请求缺少有效 CSRF 凭证
- **THEN** 系统返回 `403 AUTH_CSRF_INVALID`，会话状态保持原样

### Requirement: 单账户单登录

系统 MUST 只承认每个账号最后一次成功提交的登录会话。JWT MUST 通过签名、声明、时效和当前会话校验才能访问受保护接口。`/me` MUST 根据认证身份返回用户 ID 和用户名。账号密码错误 MUST 使用统一错误响应，登录尝试 MUST 按账号和请求来源限流。

#### Scenario: 新设备登录覆盖原设备

- **WHEN** 同一账号先在电脑登录并续期，再在手机成功登录
- **THEN** 手机可查询当前用户，电脑持有的未过期 JWT 返回 `401 AUTH_SESSION_REPLACED`，电脑的刷新或退出请求不改变手机会话

#### Scenario: 密码错误与限流

- **WHEN** 用户名不存在或密码不匹配，或调用方达到认证限流条件
- **THEN** 凭证错误返回 `401 AUTH_INVALID_CREDENTIALS`，触发限流时返回 `429 AUTH_RATE_LIMITED`，当前会话保持有效

### Requirement: 续期与重放识别

当前 Refresh Token MUST 只能成功轮换一次。轮换 MUST 沿用会话 ID 和首次登录后 7 天的绝对截止时间。Access Token 的有效期 MUST 至多为 15 分钟且不越过会话截止。只有当前会话内可识别的已消费凭证再次用于刷新时，才 MUST 撤销该会话。

#### Scenario: 轮换保留会话截止时间

- **WHEN** 用户在当前会话到期前用有效 Refresh Cookie 续期
- **THEN** 返回重新签发的凭证，原 `sessionId` 与 `sessionExpiresAt` 保持一致，新 Token 的有效期受剩余会话时间限制

#### Scenario: 当前会话的刷新凭证重复提交

- **WHEN** 同一 Refresh Token 被提交两次刷新
- **THEN** 最多一次换发成功，命中当前会话已消费记录的请求返回 `401 AUTH_REFRESH_REUSED`，撤销该会话及其后续访问资格

### Requirement: 指定会话退出与依赖故障处理

退出 MUST 同时核对请求目标、Refresh Cookie 所属会话、当前会话及已签发凭证。撤销成功后 MUST 返回 `204` 并清除 Refresh Cookie。认证依赖不可用或写入结果无法确认时 MUST 返回 `503 AUTH_SERVICE_UNAVAILABLE`，受保护资源拒绝放行。

#### Scenario: 旧标签页携带新会话 Cookie 退出

- **WHEN** 退出请求的 `sessionId` 指向旧会话，浏览器携带的 Cookie 指向新会话
- **THEN** 系统返回 `409 AUTH_SESSION_CHANGED`，保留新会话及其 Cookie

#### Scenario: 刷新先于退出提交

- **WHEN** 当前会话已经轮换，而并发退出请求携带该会话的已消费凭证
- **THEN** 可识别的凭证仍能撤销该会话，退出返回 `204`

#### Scenario: 无法取得权威会话状态

- **WHEN** 请求需要校验或变更会话，但认证依赖不可用或无法确认写入结果
- **THEN** 系统返回 `503`，既不返回受保护数据，也不确认认证操作成功

### Requirement: Vue 登录态与多标签协调

Vue MUST 提供账号密码登录、恢复中的状态、当前用户展示和退出操作。Access Token MUST 只保留在运行内存。共享 Cookie 的标签页 MUST 串行执行登录、刷新和退出，认证失败或延迟返回 MUST 只作用于对应的请求和会话。

#### Scenario: 页面重载恢复登录

- **WHEN** 页面重载且浏览器仍持有可用的 Refresh Cookie
- **THEN** 页面先初始化 CSRF，再续期并读取当前用户，最后展示已登录视图

#### Scenario: Access Token 到期

- **WHEN** 受保护请求返回 `AUTH_ACCESS_EXPIRED`
- **THEN** 页面协调同页和跨页的刷新，成功后至多重发一次原请求；刷新结果不明确时提示重新登录，不自动重试刷新

#### Scenario: 认证失败和开发入口

- **WHEN** 用户凭证失效、限流、服务暂不可用，或打开本地开发检查页
- **THEN** 登录视图展示相应错误或恢复动作，保留现有的本地开发检查能力
