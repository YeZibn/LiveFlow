## MODIFIED Requirements

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

#### Scenario: 登录超限时显示等待信息

- **WHEN** 登录接口返回 `429 AUTH_RATE_LIMITED` 和有效的 `Retry-After`
- **THEN** 页面提示服务端指定的等待秒数
