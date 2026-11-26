# 会话绑定 CSRF 生产加固设计

客户端与服务端的 HTTP 契约见 [Cookie 与 CSRF](api.md#cookie-与-csrf)，认证主线见 [登录认证设计](design.md#端到端流程)。

## 目标与信任边界

LiveFlow 通过签名双提交保护浏览器写请求。服务端同时校验 Cookie、提交值和会话绑定，让同一账号由 `S1` 切换到 `S2` 后，`S1` 的 CSRF Token 即使仍保留在客户端，也不能通过 `S2` 的写请求校验。 [签名双提交建议](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#signed-double-submit-cookie-recommended)

- 浏览器写请求通过 LiveFlow 同源入口提交。生产使用 HTTPS，业务前端和 API 使用同一 Origin。Cookie 只在当前主机上生效。其他子域属于不同 Origin，来源校验使用配置的精确值。
- 受保护业务请求的可信身份来自已验签且通过 Redis 当前会话校验的 Access Token。匿名 Cookie、CSRF Token、请求参数及尚未核对摘要的 Refresh Token 均不提供业务授权。
- 刷新和退出使用 Refresh Cookie。解析出的账号和 `sessionId` 只用于定位和选择 CSRF 签名上下文，Redis 原子操作仍负责验证凭证资格和变更会话。会话到期、单账号单会话和重放撤销沿用 [会话规则](session.md#原子操作与失败处理)。
- 本方案防止跨站伪造和跨会话复用 CSRF Token。有效 Refresh Token 已被复制时，持有者可能在首次轮换前凭它补领认证态 CSRF。Refresh Token 重放检测和会话撤销仍用于处理后续重复使用。同源脚本被攻击者控制时也能代用户发起请求。 [OWASP 威胁边界](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)

## 凭证与生命周期

采用 `HMAC-SHA-256` 签名双提交。CSRF Token 含版本、密钥标识、随机值和签名，使用规范编码。签名输入以无歧义编码包含版本、密钥标识、用途、绑定值和随机值。匿名用途使用短期预会话标识及其截止时间作为绑定上下文，认证用途使用当前登录的 `sessionId`。Token 本身无需携带明文 `sessionId`。服务端检查长度、编码、版本和用途，用恒定时间比较签名。认证态 Token 在其会话有效期内复用，Access Token 换发或 Refresh Token 轮换时无需更换。 [HMAC 签发和校验](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#hmac-csrf-token-generation-and-validation)

| 生产 Cookie | 属性及用途 |
| --- | --- |
| `__Host-lf_pre` | `HttpOnly`。服务端签发带完整性保护的随机匿名预会话标识和截止时间。有效期为 15 分钟，固定到期。匿名预会话仅用于注册和登录 CSRF 校验。 |
| `__Host-lf_csrf_pre` | Vue 可读，绑定匿名预会话，过期时间与预会话一致。注册成功后可继续用于登录。登录成功后清除这两个匿名 Cookie，并建立独立的已登录会话。 |
| `__Host-lf_csrf` | Vue 可读，绑定登录会话，`Max-Age` 使用 Redis 会话的剩余时间。登录成功时签发，确认退出成功时清除。 |

以上三种生产 Cookie 均使用 `Secure`、`Path=/`、`SameSite=Lax`，省略 `Domain`。本地回环 HTTP 使用单独的开发 Cookie 名称，通过本地配置显式允许。现有 `lf_refresh` 沿用 `HttpOnly` 与 `Path=/api/v1/auth`，其路径语义见 [Cookie 契约](api.md#cookie-与-csrf)。 [OWASP Cookie 前缀](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#using-cookies-with-host-prefixes-to-identify-origins)

匿名预会话使用签名和固定截止时间自校验，不写入 Redis。清除 Cookie 后，单独保存下来的旧匿名凭证仍可能在有效期内通过匿名校验，且只能用于匿名入口。登录成功每次都建立新的 Redis 会话。MySQL 和 Redis 会话结构无需迁移或增加逐枚 CSRF 记录。

## HTTP 接口与校验顺序

写请求统一通过 `X-XSRF-TOKEN` 提交对应 Cookie 的值。路由决定入口接受哪一种用途，服务端按入口读取指定 Cookie，验证请求头与 Cookie 一致，并校验签名及绑定。查询参数、表单值和调用方传入的会话 ID 不作为绑定依据。生产环境同时对浏览器写请求和 CSRF 签发请求执行来源校验。使用与配置精确匹配的 `Origin`，缺少时检查 `Referer` 的 Origin。明确不匹配或来源为 `null` 时拒绝，两个来源头都缺失时，写请求仍须通过 CSRF，认证态补领仍须验证当前会话凭证，匿名签发只建立短期预会话。代理提供的外部 Origin 只能来自受信配置。 [OWASP 来源校验](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#using-standard-headers-to-verify-origin)

| 入口 | 校验依据与顺序 | 成功行为 |
| --- | --- | --- |
| `GET /api/v1/auth/csrf?scope=pre-auth` | 匿名调用。服务端核对现有匿名预会话的签名及截止时间。 | 返回不可缓存的 `204`。复用有效的匿名 Cookie 对，缺失或失效时重新签发。 |
| `POST /api/v1/auth/register`、`POST /api/v1/auth/login` | 匿名预会话与对应 CSRF 先通过，再执行注册或密码验证。 | 注册沿用现有响应。登录在 Redis 确认新会话后，随现有响应签发认证态 CSRF Cookie，并清理匿名 Cookie。前端无需在成功登录后再调用一次匿名 GET。 |
| `GET /api/v1/auth/csrf?scope=session` | 请求如带 Bearer，验证该 Access Token 及 Redis 当前会话。没有 Bearer 时通过 Refresh Cookie 只读核对会话、期限和**当前**摘要。提供无效 Bearer 时返回认证失败。 | 返回不可缓存的 `204`。仅为已确认的当前会话补领认证态 CSRF，不轮换 Refresh Token、不撤销任何会话。不会降级签发匿名凭证。 |
| 登录后业务写请求 | Spring Security 先通过 Bearer 与 Redis 当前会话鉴权，再从可信安全上下文取得 `sid`，在业务方法或异步副作用开始前验证认证态 CSRF。 | 通过后进入资料修改、房间创建、WebSocket Ticket 签发或弹幕发送等业务逻辑。 |
| `POST /api/v1/auth/refresh` | 从 `lf_refresh` 解析候选会话，先验证认证态 CSRF，再交由现有 Lua 核对当前会话及当前或已消费摘要。 | 当前摘要原子轮换，沿用原 `sessionId` 和截止时间。已消费摘要走现有重放撤销。 |
| `POST /api/v1/auth/logout` | 从 `lf_refresh` 解析候选会话，验证认证态 CSRF，再核对请求所指向的会话，最后由现有 Lua 判断是否删除。 | 确认目标会话已撤销后返回 `204`，清除 Refresh Cookie 和认证态 CSRF Cookie。 |

`scope` 是加固版签发接口的必填枚举，缺失、重复或值非法返回 `400 INVALID_REQUEST`。CSRF 缺失、Cookie 与头不匹配、用途或会话绑定错误统一返回 `403 AUTH_CSRF_INVALID`，此时不会轮换、撤销或清除有效会话凭证。凭证失效、已替换、已消费重放、退出目标不一致和依赖不可用沿用 [现有错误约定](api.md#错误响应)。认证态补领遇到已消费的 Refresh Token 返回 `401 AUTH_REFRESH_INVALID`，只在真正的刷新 POST 中判定重放并撤销。

默认 `CsrfFilter` 位于 Bearer 认证之前。LiveFlow 的会话感知过滤器在 Bearer 鉴权之后、进入 MVC 之前校验请求。登录前入口在密码校验前完成 CSRF 验证，刷新和退出在执行会话变更前解析 Cookie 并验证绑定。按入口配置校验覆盖，确保每个写路由在首次进入时都经过适用的保护。MVC 内部异步响应重派发只发送原请求结果。 [Spring Security 过滤器顺序](https://docs.spring.io/spring-security/reference/7.1/servlet/architecture.html#servlet-security-filters)

## 重放检测、恢复和并发

1. 会话 `S1` 由 `R1` 刷新为 `R2` 后，绑定 `S1` 的 `C1` 仍可使用。若 `R1` 与有效 `C1` 再次提交，校验层将请求交给 Lua，Lua 命中已消费摘要后撤销 `S1` 并返回 `401 AUTH_REFRESH_REUSED`。刷新入口在验签前不会先要求提交的 Refresh 摘要等于当前摘要。
2. 重新登录建立 `S2` 后，`C1` 与 `S2` 的凭证搭配时返回 `403`。旧 `R1` 与 `C1` 一起提交时，Lua 发现当前会话已替换，返回 `401`。两种情况都保留 `S2`。
3. Vue 在同源 Web Locks 锁内协调登录、刷新和退出。恢复或刷新时已经有认证态 CSRF Cookie，直接发刷新 POST，保留旧 Refresh Token 进入重放检测的机会。Cookie 缺失时才尝试补领。为刷新补领时省略 Bearer，让签发使用同一枚 Refresh Cookie 对应的会话。
4. 若浏览器同时丢失认证态 CSRF Cookie，且 Refresh Token 已被消费，补领失败后提示重新登录。此时仅凭补领失败无法确认重放，也不会撤销 Redis 会话。若刷新 POST 响应丢失或结果不明确，前端沿用现有规则提示重新登录，避免自动提交可能已消费的旧凭证。
5. 迟到的签发响应即使把旧 CSRF Cookie 写回浏览器，旧值也过不了新会话验签。Vue 重新确认会话后可以补领，原业务写请求由用户明确重试。跨标签页状态仅用于前端协调，最终会话归属由服务端裁决。

## 生产配置与切换

- 使用 `LIVEFLOW_CSRF_SIGNING_KEY` 配置独立于 JWT 的 Base64 签发密钥，解码后至少 32 字节。用 `LIVEFLOW_CSRF_KEY_ID` 标识当前密钥，用 `LIVEFLOW_CSRF_VERIFICATION_KEYS` 提供额外验签密钥，格式为逗号分隔的 `id=Base64`。同环境实例使用一致配置。生产通过 `LIVEFLOW_CSRF_ALLOWED_ORIGINS` 配置允许来源，多个完整 HTTPS Origin 以逗号分隔。正常轮换先分发可验的新旧密钥，再切换签发密钥，最后撤除旧密钥。旧密钥保留窗口以之前签发凭证的剩余有效期为上限。密钥泄露时停止接受旧密钥并要求客户端按新密钥补领。
- 采用同源入口的前后端协调切换。切换前保证所有接流实例使用相同的 Cookie 命名、用途、签发模式和密钥。旧页面需要重载到新版客户端才能完成新的签发流程。已存在且仍有效的 Redis 登录会话可凭当前 Refresh Cookie 补领，无需清库。回退时前后端一起切回原契约。
- 指标区分 CSRF 校验失败、签发失败、认证依赖不可用与 Refresh 重放撤销。日志只记录请求 ID、路由和结果类别。原始凭证、Cookie、签名密钥及认证请求头不写入日志。
