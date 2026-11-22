## 1. 项目结构与数据基础

- [x] 1.1 按 [设计](design.md) 建立 `com.liveflow.auth` 模块结构，沿用现有 Java 21、Spring MVC 和 Maven Wrapper，确认认证需要的依赖及签名密钥配置入口。
- [x] 1.2 按 `docs/design/auth/database.md` 新增 Flyway 用户表迁移，包含 `BIGINT` 主键、ASCII 用户名、大小写不敏感的 `uk_users_username`、Argon2id 密码哈希和 UTC 时间字段。
- [x] 1.3 实现 `UserMapper` 的规范化用户名和 ID 查询，分别提供密码校验数据与 `/me` 所需公开字段。
- [x] 1.4 为本地和测试环境写明准备已有账号及配置签名密钥的办法，采用安全生成的哈希，提交的配置中只保留配置名或示例占位值。

## 2. 认证凭证与 Redis 会话

- [x] 2.1 实现 JWT 签发及验证，按 `docs/design/auth/session.md` 固定算法、Claims、签发者和受众，将有效期限制在 15 分钟及剩余会话期限内。
- [x] 2.2 实现 Refresh Token 的生成、解析和摘要计算，保证 Redis 和日志的凭证处理符合会话设计。
- [x] 2.3 实现会话仓储的当前会话读取、原子替换、轮换与定向撤销，保持绝对到期，区分已消费重放、未知摘要和旧会话，并正确映射 Redis 故障。
- [x] 2.4 实现 `AuthService` 的密码校验、按账号与请求来源限流、登录、刷新和退出。仅在所需会话变更得到确认后返回成功。

## 3. Spring MVC 与安全过滤链

- [x] 3.1 接入 Bearer 验证与 Redis 当前会话检查，将已认证身份交给 MVC。保留 Actuator 健康接口和 `local` WebSocket 探测的访问规则。
- [x] 3.2 接入 SPA CSRF 初始化与 POST 校验，配置同源 `XSRF-TOKEN`、`X-XSRF-TOKEN` 与 `lf_refresh` 的写入、轮换和清除，生产默认启用 Secure。
- [x] 3.3 按 `docs/design/auth/api.md` 实现 `csrf`、`login`、`refresh`、`logout`、`me` 五个接口及 DTO 校验，遵循 Cookie、空响应和不可缓存响应约定。
- [x] 3.4 统一安全过滤器与 MVC 的 `{code,message}`。核对无效或过期凭证、会话被替换、重放、CSRF、旧会话退出、限流及 `503` 的响应。

## 4. Vue 认证界面与登录态

- [x] 4.1 在现有 Vue 应用中增加登录表单、恢复中界面、当前用户与退出入口，保留开发检查页。补齐标签关联、键盘提交、提交中状态和中文错误提示。
- [x] 4.2 实现类型化认证 HTTP 客户端，通过现有 Vite 同源代理调用接口，按接口注入 Bearer 或 CSRF 请求头，处理 Cookie、`204` 和结构化错误。
- [x] 4.3 实现内存会话状态与应用启动恢复。按 CSRF 初始化、续期、`/me` 顺序加载。完成登录与退出后的 CSRF 更新及界面切换。
- [x] 4.4 实现同页刷新合并和跨标签页会话写操作排他协调。在锁内复核会话归属，防止旧操作结果清除新状态。为缺少安全互斥能力的环境提供明确提示。
- [x] 4.5 接入过期后的单次续期及原请求重发，限定重发前后会话一致。处理替换、重放、`AUTH_SESSION_CHANGED`、刷新结果不确定、限流和暂不可用。

## 5. 注释、可读性与联调

- [x] 5.1 保留并同步已有中文注释。为 Java 跨边界方法补 Javadoc，注明适用的参数、返回、副作用与异常。为 TypeScript 认证入口补 TSDoc，为 Lua 标明状态比较、原子提交和返回语义。走查命名、分层和敏感信息处理。
- [x] 5.2 用后端测试覆盖五个接口、JWT 校验、Cookie/CSRF、Redis 暂不可用与错误映射。用实际 Redis 验证同 Token 并发刷新、换设备覆盖、旧请求保护和刷新先于退出。
- [x] 5.3 用浏览器联调登录、重载恢复、Access Token 到期和退出。验证双标签页串行化、旧标签页退出保护、迟到响应、刷新不确定和用户提示。复查开发检查页及 WebSocket 探测。
- [x] 5.4 运行 `./mvnw clean verify` 和 `npm ci && npm run build`，记录已跑场景及限制。在 README 补充认证联调入口及环境配置说明。
