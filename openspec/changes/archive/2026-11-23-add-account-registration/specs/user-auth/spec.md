## ADDED Requirements

### Requirement: 账号密码自助注册

系统 MUST 接受 `POST /api/v1/auth/register` 的 `username` 和 `password` 两个字符串字段。账号 MUST 原样匹配 `[A-Za-z0-9][A-Za-z0-9_]{2,31}`。新密码 MUST 为 10–25 个 ASCII `0x21–0x7E` 字符，且含大写字母、小写字母、数字和至少一个该范围内的标点。账号名按原始拼写唯一。写入成功 MUST 返回 `201`、十进制字符串 `userId`、原样 `username` 和 `Cache-Control: no-store`，登录会话由登录接口建立。

#### Scenario: 注册成功并使用新账号登录

- **WHEN** 匿名用户用有效 CSRF 提交合法且未被使用的账号名和密码
- **THEN** 注册返回 `201` 与新账号摘要，用户随后可用准确的账号名和密码登录

#### Scenario: 密码或请求格式错误

- **WHEN** 请求含未知字段、字段缺失或类型无效，或密码长度、字符范围或字符组合不符合要求
- **THEN** 注册返回 `400 INVALID_REQUEST`，数据库中无本次创建的账号

#### Scenario: 完全同名并发注册

- **WHEN** 两个合法注册请求同时提交完全相同的账号名
- **THEN** 一个请求返回 `201`，另一个返回 `409 AUTH_USERNAME_ALREADY_EXISTS`，账号只创建一次

### Requirement: 注册限流与故障处理

系统 MUST 在计算密码哈希前按可信来源 IP 对格式有效的注册提交限流。初始设置为 600 秒固定窗口内最多 10 次，注册和登录分别计数。超限 MUST 返回 `429 AUTH_RATE_LIMITED` 和以整数秒表示的 `Retry-After`。所需的限流或写入依赖不可用，或写入结果无法确认时，系统 MUST 返回 `503 AUTH_SERVICE_UNAVAILABLE`。

#### Scenario: 注册超过来源额度

- **WHEN** 同一来源用完当前注册窗口的可用次数，再提交格式有效的注册请求
- **THEN** 接口返回 `429` 与剩余等待秒数，本次请求无法创建账号

#### Scenario: 注册依赖不可用

- **WHEN** 系统无法检查注册限流或确认账号写入
- **THEN** 接口返回 `503`，前端提示用户稍后再试，写入结果不明确时可先尝试登录

### Requirement: Vue 注册表单

Vue MUST 在当前登录入口提供注册及返回登录的操作。表单包含账号、密码和确认密码，两个密码框默认隐藏，各自可以显示或隐藏。两次密码 MUST 逐字符一致才提交。注册成功后 MUST 清空两份密码，切回登录并按原样预填账号名。注册失败时 MUST 提示有效的错误和处理方式。

#### Scenario: 确认密码不一致

- **WHEN** 用户提交两个密码输入不同的注册表单
- **THEN** 页面提示两次密码不一致，注册请求保持未提交

#### Scenario: 分别切换密码可见性

- **WHEN** 用户操作任一密码框的显示或隐藏按钮
- **THEN** 只改变对应输入框的可见状态，两处输入值保持原样

#### Scenario: 注册后返回登录

- **WHEN** 注册成功
- **THEN** 页面清空两个密码框，回到登录表单并预填原样用户名

## MODIFIED Requirements

### Requirement: 认证接口与浏览器凭证

系统 MUST 按 `docs/design/auth/api.md` 的认证接口契约提供 `GET /api/v1/auth/csrf`、`POST /api/v1/auth/register`、`POST /api/v1/auth/login`、`POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout` 和 `GET /api/v1/auth/me`。四个 POST MUST 校验 CSRF。登录和刷新成功 MUST 通过 JSON 返回 Access Token，通过 `HttpOnly` Refresh Cookie 传递刷新凭证，并标记响应不可缓存。生产 Cookie MUST 使用 `Secure`。

#### Scenario: 初始化与登录凭证交付

- **WHEN** 浏览器匿名获取 CSRF Cookie，再携带有效 CSRF 请求头和正确账号密码登录
- **THEN** CSRF 初始化返回 `204`，登录返回 `200`、`TokenResponse` 和 `lf_refresh` Cookie

#### Scenario: 请求缺少有效 CSRF

- **WHEN** 注册、登录、刷新或退出请求缺少有效 CSRF 凭证
- **THEN** 系统返回 `403 AUTH_CSRF_INVALID`，账号和会话状态保持原样

### Requirement: 单账户单登录

系统 MUST 只承认每个账号最后一次成功提交的登录会话。JWT MUST 通过签名、声明、时效和当前会话校验才能访问受保护接口。`/me` MUST 根据认证身份返回用户 ID 和原样用户名。登录 MUST 按原始用户名精确匹配，账号密码错误 MUST 使用统一错误响应。登录尝试 MUST 按原样账号和请求来源限流。

#### Scenario: 新设备登录覆盖原设备

- **WHEN** 同一账号先在电脑登录并续期，再在手机成功登录
- **THEN** 手机可查询当前用户，电脑持有的未过期 JWT 返回 `401 AUTH_SESSION_REPLACED`，电脑的刷新或退出请求不改变手机会话

#### Scenario: 密码错误与限流

- **WHEN** 用户名不存在或密码不匹配，或调用方达到认证限流条件
- **THEN** 凭证错误返回 `401 AUTH_INVALID_CREDENTIALS`，触发限流时返回 `429 AUTH_RATE_LIMITED`，当前会话保持有效

#### Scenario: 大小写不同的账号分别登录

- **WHEN** 分别注册 `Alice` 和 `alice` 并使用各自的准确拼写和正确密码登录
- **THEN** 两次登录归属不同用户 ID，`/me` 返回相应的原始拼写；交换拼写或密码造成凭证不匹配时返回 `401 AUTH_INVALID_CREDENTIALS`
