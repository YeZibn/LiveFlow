## Why

LiveFlow 现行 CSRF 校验依赖浏览器提交值与 Cookie 一致，服务端没有把该值绑定到具体登录会话。公网部署需要让会话替换同时取消旧 CSRF 凭证对新会话的校验资格，并保留现有 Refresh Token 重放撤销。

## What Changes

- 注册和登录使用短期匿名预会话。登录确认后签发绑定新 `sessionId` 的认证态 CSRF，凭证刷新时沿用该绑定。
- 为 Bearer 业务写请求、刷新和退出指定各自的绑定依据及校验顺序。认证态补领只读验证当前凭证，实际刷新继续由 Redis 原子判断轮换及重放。
- **BREAKING** 浏览器和其他调用者需要按操作选择 CSRF Cookie，签发请求需要显式传入 `scope`。前后端按新契约协调切换。

## Capabilities

### New Capabilities

### Modified Capabilities

- `user-auth` 的 CSRF 签发、登录凭证交付、写请求校验、刷新与退出，以及 Vue 恢复流程。

## Impact

- 后端涉及 Spring Security、认证控制器、凭证签发与 Redis 会话只读确认。前端涉及 CSRF 请求头、登录状态及跨标签页协调。
- 部署需要配置独立签发密钥与公开 Origin。同一环境的应用实例使用一致配置。现有会话可补领凭证，部署无需 MySQL 迁移或 Redis 会话结构调整。
- [会话绑定 CSRF 生产加固设计](../../../../docs/design/auth/security.md)
