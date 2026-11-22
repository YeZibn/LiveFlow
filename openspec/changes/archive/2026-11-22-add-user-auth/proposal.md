## Why

LiveFlow 需要让已有账号在 Vue 客户端登录、恢复登录和退出，并在换设备登录后及时阻止旧会话访问受保护的 HTTP 接口。这项变更把 `docs/design/auth/design.md` 中已定稿的认证方案转成前后端共同实施的契约。

## What Changes

- 为已有账号提供密码登录、当前用户查询、凭证续期、退出和 CSRF 初始化。
- 用 MySQL 持久化用户账号，用 Redis 维护每账号唯一的当前会话，以及 Refresh Token 的轮换、重放识别与撤销。
- 补齐 Vue 登录页、页面启动恢复、已登录视图和退出交互。多个标签页协调对同一浏览器会话的变更，并统一处理认证失败。
- 保留开发检查页和现有探测入口。WebSocket Ticket 与直播间接入由后续变更处理。

## Capabilities

### New Capabilities

- `user-auth`：HTTP 认证、凭证与会话生命周期、Vue 登录态及相关交互。

### Modified Capabilities

## Impact

- 后端涉及 Spring MVC、Spring Security、MyBatis-Plus、Flyway 和 Redis Lua。
- 前端涉及 Vue、TypeScript 和现有 Vite 同源代理。
- 运行配置涉及 JWT 签名密钥及 Refresh Cookie 的环境设置。需要可重复准备的本地测试账号。
