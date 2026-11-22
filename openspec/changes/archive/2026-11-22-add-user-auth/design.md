## Context

见 [proposal.md](proposal.md) 的变更背景。当前 Spring Security 过滤链开放 Actuator 健康检查，以及 `local` 下的 WebSocket 探测。Vue 目前提供开发检查页，Vite 已配置同源 `/api` 代理。已有 HTTP 契约、MySQL 表和索引、Redis 会话规则分别维护在 `docs/design/auth/api.md`、`docs/design/auth/database.md`、`docs/design/auth/session.md`。本文件约定实现组织和前后端衔接，系统行为见 [增量规格](specs/user-auth/spec.md)。

## Goals / Non-Goals

**Goals**

- 在当前单个 Spring Boot 应用内实现 MVC 认证模块，统一 Spring Security 与 MVC 的错误输出。
- 让 Vue 用户能完成登录、页面恢复、当前用户展示、续期和退出，并能理解认证失败后的下一步操作。
- 保留现有入口的中文注释和运行行为，为新增跨边界方法提供可读的文档注释。

**Non-Goals**

- 账号注册、密码找回、第三方登录和账号管理界面。
- WebSocket Ticket、业务长连接鉴权、房间和弹幕功能。

## Decisions

### 后端以认证模块组织 MVC

在 `com.liveflow.auth` 下按 Controller 与 DTO、Service、Repository 和 Security 职责组织代码。`AuthController` 负责 HTTP 输入与 Cookie。`AuthService` 编排账号、会话和凭证。`AccessTokenService` 封装 JWT。`UserMapper` 只提供当前读路径，`AuthSessionRepository` 封装 Redis 命令与 Lua。现有基础设施配置协同接入，避免两条过滤链对同一路径给出不同授权规则。过滤器的认证入口和拒绝处理器与 MVC 异常处理复用相同错误表示。

受保护请求先做 JWT 签名、受众、签发者和时效校验，再查询 Redis。采用逐请求核对会话的原因是被替换的 Access Token 在有效期内也要拒绝放行。登录、刷新与退出使用各自的凭证和 CSRF 规则。Spring Security 在请求进入 Controller 前完成适用的 CSRF 校验。

### Redis 是会话状态的裁决点

登录替换、续期轮换及定向撤销由单账号 Redis Hash 的 Lua 脚本裁决。响应交付以状态变更得到明确确认作为前提。一次正确登录生成全新的会话 ID，刷新保留会话 ID 和绝对截止时间。发现旧会话 ID 时直接拒绝该请求，避免误撤销后来建立的会话。Redis 不可用时统一返回暂不可用，并区分明确的凭证失败与结果不确定的写入。保持现有 `users` 字段、唯一索引、凭证格式和时长约定。

### Vue 按视图、会话状态与 HTTP 客户端分工

- **视图**负责账号密码表单、加载和提交提示、当前用户与退出入口。会话恢复期间先显示独立的加载状态。保持表单标签、键盘提交和错误提示可访问，登录和退出期间阻止重复提交。保留原开发检查页的入口及行为。
- **会话状态**集中持有当前用户、Access Token 和 `sessionId`，以恢复中、未登录、已登录和可重试错误区分主要界面。首次进入先初始化 CSRF，再尝试续期，取得凭证后调用 `/me`。确定没有有效会话时进入登录页，依赖故障时提供明确的恢复提示。
- **HTTP 客户端**走相对 `/api` 路径，按接口决定 Bearer、Cookie 和 CSRF。为 `204` 单独处理空响应。仅 `AUTH_ACCESS_EXPIRED` 触发续期和一次原请求重发。同页请求复用进行中的刷新。刷新使用新的会话状态后，只有与原请求会话一致时才允许自动重发。
- **多标签协调**用同源 Web Locks 为登录、刷新和退出提供同一把排他锁，在锁内发起请求并处理结果。进入锁后复核操作发起时所见的会话；退出始终提交原目标 `sessionId`。必要时通过同源消息通知其他标签页状态变化，消息仅携带会话标识或事件类型。Access Token 留在标签页内存。缺少跨标签互斥能力时展示清楚的兼容提示。选用浏览器原生排他机制，避免把 `localStorage` 读写当作原子锁。 [W3C Web Locks](https://www.w3.org/TR/web-locks/)
- **迟到结果与失败**按操作绑定的会话及本页操作顺序处理。旧请求失败时仅清理它对应的本地状态。`AUTH_SESSION_CHANGED` 保留新会话，刷新结果不明时提示重新登录。CSRF 失败引导刷新 CSRF 凭证，限流与服务异常显示可理解的中文提示。

### 代码和注释约定

沿用 Java 21、Spring MVC、Vue 3 和 TypeScript 现有工具链。保留安全过滤链、探测、配置和 Vite 代理中仍准确的注释，受变更影响的注释随逻辑同步。Java 新增跨边界方法用中文 Javadoc 说明职责、参数、返回或副作用及适用的异常。TypeScript 的认证请求、恢复、刷新、退出与互斥入口用 TSDoc 说明输入、返回和失败语义。Lua 注释说明会话匹配、原子提交点和返回值含义。注释解释约束和原因，使用明确命名表达普通代码步骤。

## Risks / Trade-offs

- **Redis 故障影响认证可用性** → 将失败映射到契约中的 `503`，拒绝放行并保留明确定义的前端提示。
- **Refresh Cookie 已变更但响应丢失** → 客户端停止自动续期重试并引导重新登录。后端保持严格重放检测。
- **多个标签页同时刷新或旧页面退出** → 客户端串行化会话写请求，服务端继续以会话 ID 和摘要判断，旧结果只影响对应的界面状态。
- **本地回环 HTTP 与生产 HTTPS 的 Cookie 设置有差异** → `Secure` 生产默认开启，本地覆盖限于显式开发配置。

## Migration Plan

1. 用 Flyway 建立 `users`，按已确认的主键和用户名唯一索引落盘。在本地或测试环境准备已有账号，使用密码哈希并避免提交测试明文凭证。
2. 配置签名密钥与 Cookie 属性，部署后端后再启用 Vue 认证流。保留现有探测入口和 Vite 代理通路。
3. 回滚应用时前后端协调切回旧版，保留已建用户表。Redis 会话按既有有效期清理。再次启用时按当时有效的签名密钥和会话状态校验凭证。
