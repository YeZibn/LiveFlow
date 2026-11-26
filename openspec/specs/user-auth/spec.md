# user-auth Specification

## Purpose

为 LiveFlow 的 Vue 客户端建立统一的已有账号认证能力，覆盖密码登录、页面恢复、受保护的 HTTP 请求、凭证轮换和退出，并确保换设备登录或会话撤销后可以按会话边界拒绝旧请求。

## Requirements

### Requirement: 认证接口与浏览器凭证

系统 MUST 提供 `GET /api/v1/auth/csrf`、`POST /api/v1/auth/register`、`POST /api/v1/auth/login`、`POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout` 和 `GET /api/v1/auth/me`。登录和刷新成功 MUST 通过 JSON 返回 Access Token，通过 `HttpOnly` Refresh Cookie 传递刷新凭证，并标记响应不可缓存。生产 Cookie MUST 使用 `Secure`。

CSRF 签发 GET MUST 接收且仅接收一个合法 `scope`，允许 `pre-auth` 和 `session`。缺失、重复或非法的 `scope` 返回 `400 INVALID_REQUEST`。匿名签发 MUST 返回不可缓存的 `204`，复用仍有效的匿名预会话及其签名 CSRF，缺失或失效时重新签发。认证态补领 MUST 先验证请求显式提供的 Bearer 及当前 Redis 会话。请求未带 Bearer 时，MUST 只读验证 Refresh Cookie 对应当前会话及当前摘要。提供无效 Bearer 时 MUST 返回认证失败。补领成功返回不可缓存的 `204` 和当前会话的认证态 CSRF Cookie。补领不轮换、不撤销会话，也不签发匿名凭证作为失败回退。

注册和登录 MUST 验证匿名预会话及其绑定的 CSRF。登录成功 MUST 清除匿名 Cookie，并直接签发绑定本次新会话的认证态 CSRF。认证后的 Bearer 业务写请求 MUST 使用已验证身份中的当前 `sid` 校验认证态 CSRF，刷新和退出 MUST 用 Refresh Cookie 的会话上下文校验。各写入口 MUST 比较预期用途的 Cookie 与请求头 `X-XSRF-TOKEN` 并验证签名，客户端自行提供的会话 ID 不作为绑定依据。浏览器来源明确不匹配或为 `null` 时 MUST 拒绝写请求和签发；缺少来源头时仍执行相应的凭证与 CSRF 校验。

生产的匿名预会话 Cookie MUST 为 `HttpOnly`，匿名和认证态 CSRF Cookie MUST 可由同源 Vue 读取。三种 Cookie MUST 使用不同的 `__Host-` 名称、`Secure`、`SameSite=Lax`、`Path=/` 并省略 `Domain`。认证态 CSRF 的有效期不得超过会话截止时间。

#### Scenario: 初始化与登录凭证交付

- **WHEN** 浏览器请求 `scope=pre-auth`，再携带有效匿名 CSRF 和正确账号密码登录
- **THEN** 初始化返回 `204`，登录返回 `200`、`TokenResponse`、`lf_refresh` 和绑定新会话的认证态 CSRF Cookie，清除匿名 Cookie

#### Scenario: 请求缺少有效 CSRF

- **WHEN** 注册、登录、刷新、退出或受保护业务写请求的 CSRF 缺失、格式错误、签名无效、Cookie 与头不匹配，或用途不符
- **THEN** 返回 `403 AUTH_CSRF_INVALID`，账号、会话和业务状态均保持原样

#### Scenario: 跨会话或跨来源的请求

- **WHEN** 客户端向新会话提交旧会话的 CSRF，或请求携带不受信任的来源
- **THEN** 返回 `403 AUTH_CSRF_INVALID` 并保留当前会话及其凭证，即使其他请求条件有效

#### Scenario: 补领认证态 CSRF

- **WHEN** 请求 `scope=session` 且此前认证会话仍有效，携带有效 Bearer，或在不带 Bearer 时携带当前尚未消费的 Refresh Cookie
- **THEN** 返回该会话的认证态 CSRF，已有 Refresh Token 及会话截止保持不变

#### Scenario: 补领凭证无效或已消费

- **WHEN** 请求认证态补领时凭证无效，或无 Bearer 且只携带已消费的 Refresh Cookie
- **THEN** 返回相应的认证失败，已消费 Refresh Cookie 返回 `401 AUTH_REFRESH_INVALID`，响应不签发匿名或认证态 CSRF，也不撤销当前会话

### Requirement: 单账户单登录

系统 MUST 只承认每个账号最后一次成功提交的登录会话。JWT MUST 通过签名、声明、时效和当前会话校验才能访问受保护接口。`/me` MUST 根据认证身份返回用户 ID 和原样用户名。登录 MUST 按原始用户名精确匹配，账号密码错误 MUST 使用统一错误响应。登录 MUST 在格式校验后、密码校验前，按原样账号和可信来源 IP 执行独立的固定窗口。默认各 60 秒，分别最多 10 次和 60 次，从各维度首次计数起计算。两个维度 MUST 一起裁决，全部通过才同时计数，拒绝不扣任一维度的额度，也不延长窗口。超限 MUST 返回不可缓存的 `429 AUTH_RATE_LIMITED` 和整数秒 `Retry-After`，取所有已超限维度中最长的剩余时间，向上取整且最少 1 秒。限流状态无法确认时 MUST 返回 `503 AUTH_SERVICE_UNAVAILABLE`。

#### Scenario: 新设备登录覆盖原设备

- **WHEN** 同一账号先在电脑登录并续期，再在手机成功登录
- **THEN** 手机可查询当前用户，电脑持有的未过期 JWT 返回 `401 AUTH_SESSION_REPLACED`，电脑的刷新或退出请求不改变手机会话

#### Scenario: 密码错误与限流

- **WHEN** 用户名不存在或密码不匹配，或调用方达到认证限流条件
- **THEN** 凭证错误返回 `401 AUTH_INVALID_CREDENTIALS`，触发限流时返回 `429 AUTH_RATE_LIMITED` 及 `Retry-After`，当前会话保持有效

#### Scenario: 大小写不同的账号分别登录

- **WHEN** 分别注册 `Alice` 和 `alice` 并使用各自的准确拼写和正确密码登录
- **THEN** 两次登录归属不同用户 ID，`/me` 返回相应的原始拼写；交换拼写或密码造成凭证不匹配时返回 `401 AUTH_INVALID_CREDENTIALS`

#### Scenario: 两个登录维度分别达到上限

- **WHEN** 同一登录请求的账号和来源窗口均已满，且截止时间不同
- **THEN** 返回较长的剩余等待秒数，两个计数与截止时间均不因拒绝而改变

#### Scenario: 大小写不同的账号分别计入额度

- **WHEN** 原样账号 `Alice` 耗尽账号额度，随后从仍有来源额度的 IP 使用 `alice` 发起格式有效的请求
- **THEN** `alice` 使用独立的账号额度

#### Scenario: 无法确认登录限流状态

- **WHEN** 格式有效的登录请求无法确认限流状态
- **THEN** 返回 `503 AUTH_SERVICE_UNAVAILABLE`，本次不继续校验密码或替换会话

### Requirement: 续期与重放识别

当前 Refresh Token MUST 只能成功轮换一次。轮换 MUST 沿用会话 ID 和首次登录后 7 天的绝对截止时间。Access Token 的有效期 MUST 至多为 15 分钟且不越过会话截止。刷新 MUST 先通过认证态 CSRF 校验，再原子区分当前摘要、已消费摘要和无效或不匹配的会话。当前会话内可识别的已消费凭证再次通过 CSRF 校验用于刷新时，系统 MUST 撤销该会话。

#### Scenario: 轮换保留会话截止时间

- **WHEN** 用户在当前会话到期前用有效 Refresh Cookie 和同会话的 CSRF 续期
- **THEN** 返回重新签发的凭证，原 `sessionId` 与 `sessionExpiresAt` 保持一致，新 Token 的有效期受剩余会话时间限制，原认证态 CSRF 仍可使用

#### Scenario: 当前会话的刷新凭证重复提交

- **WHEN** 同一 Refresh Token 与同会话的有效 CSRF 两次提交刷新
- **THEN** 最多一次换发成功，命中当前会话已消费记录的请求返回 `401 AUTH_REFRESH_REUSED`，撤销该会话及其后续访问资格

#### Scenario: 重放请求缺少有效的会话绑定

- **WHEN** 已消费的 Refresh Token 搭配无效 CSRF 提交，或旧会话已被新登录替换
- **THEN** 无效 CSRF 返回 `403`。携带旧会话有效 CSRF 的旧凭证在会话已替换时返回 `401 AUTH_REFRESH_INVALID`。两者均不撤销当前会话

### Requirement: 指定会话退出与依赖故障处理

退出 MUST 先验证认证态 CSRF，再核对请求目标、Refresh Cookie 所属会话、当前会话及已签发凭证。撤销成功后 MUST 返回 `204` 并清除 Refresh Cookie 与认证态 CSRF Cookie。认证依赖不可用或写入结果无法确认时 MUST 返回 `503 AUTH_SERVICE_UNAVAILABLE`，受保护资源拒绝放行。

#### Scenario: 旧标签页携带新会话 Cookie 退出

- **WHEN** 退出请求的 `sessionId` 指向旧会话，浏览器携带的新会话 Refresh Cookie 和认证态 CSRF 均有效
- **THEN** 返回 `409 AUTH_SESSION_CHANGED`，保留新会话及其 Cookie

#### Scenario: 刷新先于退出提交

- **WHEN** 当前会话已经轮换，而并发退出请求携带该会话已消费但可识别的 Refresh Token 和有效 CSRF
- **THEN** 仍可撤销该会话，退出返回 `204` 并清除相关认证 Cookie

#### Scenario: 无法取得权威会话状态

- **WHEN** 请求需要校验或变更会话，但认证依赖不可用或无法确认写入结果
- **THEN** 系统返回 `503`，不返回受保护数据，也不确认认证操作成功

### Requirement: Vue 登录态与多标签协调

Vue MUST 提供账号密码登录、恢复中的状态、当前用户展示和退出操作。Access Token MUST 只保留在运行内存。共享 Cookie 的标签页 MUST 串行执行登录、刷新和退出，认证失败或延迟返回 MUST 只作用于对应的请求和会话。前端按操作选择 CSRF Cookie，刷新前只有缺少认证态 CSRF Cookie 时才补领。为刷新补领时 MUST 省略 Bearer，使用本次刷新将携带的 Refresh Cookie。

#### Scenario: 页面重载恢复登录

- **WHEN** 页面重载且浏览器仍持有可用的 Refresh Cookie
- **THEN** 已持有认证态 CSRF 时直接续期，缺失时先补领再续期，成功后读取当前用户并展示已登录视图

#### Scenario: Access Token 到期

- **WHEN** 受保护请求返回 `AUTH_ACCESS_EXPIRED`
- **THEN** 页面协调同页和跨页的刷新，成功后至多重发一次原请求，适用更严格的业务提交规则时遵循该规则。刷新结果不明确时提示重新登录，不自动重试刷新

#### Scenario: 刷新前补领失败

- **WHEN** 浏览器缺少认证态 CSRF 且认证态补领因 Refresh Token 已消费而失败
- **THEN** 页面停止自动续期，引导重新登录，当前 Redis 会话不会被补领请求撤销

#### Scenario: 认证失败和开发入口

- **WHEN** 用户凭证失效、限流、服务暂不可用，或打开本地开发检查页
- **THEN** 登录视图展示相应错误或恢复动作，保留现有的本地开发检查能力

#### Scenario: 登录超限时显示等待信息

- **WHEN** 登录接口返回 `429 AUTH_RATE_LIMITED` 和有效的 `Retry-After`
- **THEN** 页面提示服务端指定的等待秒数

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
