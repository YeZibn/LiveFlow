## Context

LiveFlow 使用 Spring MVC、MySQL、MyBatis 和 Vue，受保护请求已由 Spring Security、JWT 和 Redis 当前会话校验。个人主页按会话挂载，`withAccess` 支持在明确的令牌过期错误后续期重发。房间设计契约已分别维护。 [模块设计](../../../../docs/design/room/design.md) · [HTTP 契约](../../../../docs/design/room/api.md) · [数据库设计](../../../../docs/design/room/database.md)

## Goals / Non-Goals

**Goals**

- 让创建与本人查询贯通数据库、MVC 和 Vue，并以数据库持久化结果为准。
- 保证房主归属、请求校验、同账号并发裁决和跨会话状态隔离。
- 在开发任务中明确方法注释、关键语句注释与回归验证。

**Non-Goals**

- 房间搜索、公开访问、房间资料修改、开播状态、WebSocket 和弹幕属于后续变更。

## Decisions

### 以 MySQL 唯一约束裁决创建

在 `liveflow-core` 创建 `rooms` 表，房主外键引用 `users.id`，用 `uk_rooms_owner_user_id` 同时支持本人查询和一人一房。Service 校验名称后开启写事务，Mapper 按可信账号执行一次绑定参数插入。同事务按房主回读生成的标识和创建时间，提交后返回成功。只有房主唯一约束冲突映射为 `ROOM_ALREADY_EXISTS`，存储与事务异常走专属错误。数据库约束覆盖所有应用实例，也能保留并发先创建的名称。

### 沿用现有 MVC 安全边界

在 `liveflow-api` 按方法注册 GET 和 POST 路由，Controller 从认证 JWT `sub` 取得账号 ID。POST 沿用浏览器写请求的 CSRF 校验。Controller 处理严格 JSON 结构和 HTTP 转换，Service 管理名称规则和事务，Mapper 负责 SQL。本人查询区分账号不存在和账号存在但无房间。成功响应不可缓存，GET 保留显式 `room: null`。使用现有请求层和认证错误结构。

### 个人主页独立管理房间状态

挂载独立房间组件，资料和房间各自加载并呈现失败。房间请求经 `withAccess`，利用现有会话绑定，组件卸载后丢弃迟到结果。用服务端返回的名称展示已创建房间，创建冲突或结果不明确时通过 GET 确认。查询返回空后由用户重新提交，写请求超时后无自动重发。复用现有表单及反馈样式，房间名称通过 Vue 纯文本插值展示。

## Risks / Trade-offs

- 创建响应可能在提交后丢失 → 使用本人查询确认权威结果，保留输入至结果明确。
- Java 与浏览器对空白或字符串长度的默认行为不同 → 以后端契约为准，按约定的 Unicode 空白与码点计数，并覆盖空白边界与补充平面字符。
- 房间外键限制账号直接删除 → 首版采用 `ON DELETE RESTRICT`，集成测试按房间、账号顺序清理专用数据。

## Migration Plan

随应用启动执行 Flyway `V4__create_rooms.sql`，在表可用后启用新 API 与 Vue 入口。旧账号无需回填。应用回退时保留表和已创建记录，重新部署后恢复读取。
