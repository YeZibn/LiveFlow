# 认证凭证与 Redis 会话

单登录的选择依据和端到端流程见 [登录认证设计](design.md#方案选择与设计思路)。浏览器携带凭证的方式见 [Cookie 与 CSRF](api.md#cookie-与-csrf)。

## 单会话约束

Redis 按账号维护唯一的当前登录会话。每次密码登录生成新的会话 ID，在 Redis 原子提交时替换该账号先前的会话。同一会话中的多个标签页可以各自持有有效 Access Token。刷新保留会话 ID 和首次登录的截止时间。

## 凭证格式

### Access Token

Access Token 使用 HS256，签名密钥由部署环境注入。每次签发的过期时间取签发后 15 分钟与会话截止时间中的较早值。JWT 验签和 Claims 校验通过后，业务请求还需验证 Redis 当前会话。 [JWT 标准字段](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1) · [Spring Security JWT 校验](https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/jwt.html)

| Claim | 类型 | 值 |
| --- | --- | --- |
| `iss` | string | `liveflow` |
| `aud` | string array | `["liveflow-api"]` |
| `sub` | string | MySQL 用户 ID 的十进制字符串 |
| `sid` | string | 本次登录的会话 UUID |
| `iat` | integer | 签发时刻，Unix 秒 |
| `exp` | integer | 过期时刻，Unix 秒 |

### Refresh Token

Refresh Token 格式为 `rt1.<userId>.<sessionId>.<secret>`。`userId` 与 `sessionId` 用于定位记录。`secret` 为 32 字节安全随机数的 Base64URL 编码。Redis 保存完整 Token 的 SHA-256 摘要，凭证归属由摘要匹配结果确定。轮换沿用会话的绝对截止时间。 [RFC 9700 刷新令牌轮换](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2)

## Redis 数据结构

键为 `liveflow:auth:session:{userId}`，值为 Hash。使用 `expiresAtMs` 设置整个 Hash 的绝对过期时间，同一会话中的已消费记录随 Hash 一起到期。 [Redis 绝对过期](https://redis.io/docs/latest/commands/pexpireat/)

| Hash 字段 | 值 | 语义 |
| --- | --- | --- |
| `sessionId` | UUID 字符串 | 当前会话的 ID |
| `refreshHash` | SHA-256 摘要 | 当前可用于轮换的 Refresh Token |
| `createdAtMs` | Unix 毫秒 | 本次登录建立时间 |
| `expiresAtMs` | Unix 毫秒 | 创建后 7 天的会话绝对截止时间 |
| `used:<digest>` | `1` | 当前会话内已消费的 Refresh Token 摘要 |

## 原子操作与失败处理

由 `AuthSessionRepository` 执行 Redis Lua 脚本。修改会话的脚本应在同一次执行中完成条件检查和写入。 [Redis Lua 原子执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)

| 操作与前置条件 | 结果 |
| --- | --- |
| 登录通过密码认证 | 原子替换整个 Hash 并设置绝对过期时间，旧会话及其摘要历史随旧记录移除。成功提交后才返回新凭证。 |
| 受保护请求携带校验通过的 JWT | 查询 `sub` 对应的当前会话。会话存在且 `sid` 匹配才通过。 |
| 刷新时会话匹配，摘要等于 `refreshHash` | 写入 `used:<旧摘要>`，替换 `refreshHash`，保留 `sessionId` 和原过期时间。 |
| 刷新时会话匹配，摘要命中 `used:<digest>` | 删除当前会话，返回 `AUTH_REFRESH_REUSED`。同一会话此前签发的凭证在下一次验证时失效。 |
| 刷新时会话已被替换、已过期或摘要未知 | 返回 `AUTH_REFRESH_INVALID`，保留已有的当前会话。 |
| 退出目标、Refresh Token 所属会话和 Redis 当前会话匹配，且摘要可识别 | 删除当前会话。当前摘要与该会话已消费的摘要均可用于退出。 |
| 退出目标与 Cookie 会话不同，或凭证无法验证 | 根据 [HTTP 契约](api.md#错误响应) 返回 `409` 或 `401`，保留当前会话和 Cookie。 |

刷新、退出与重新登录近同时发生时，Redis 的原子执行顺序决定写入结果。旧请求在任何顺序下都只能改变它匹配的会话。Redis 超时或故障时按 [认证接口约定](api.md#错误响应) 返回 `503`，鉴权拒绝放行。

会话绑定 CSRF 的生产加固方案复用同一会话生命周期。刷新、退出与认证态 CSRF 补领各自的凭证资格见 [重放检测、恢复和并发](security.md#重放检测恢复和并发)。
