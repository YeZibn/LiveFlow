## Why

LiveFlow 当前需要在数据库中预置账号才能登录。自助注册让用户可以从 Vue 页面创建账号，再通过现有认证流程进入个人主页。

## What Changes

- 增加账号与密码注册接口和 Vue 注册表单。用户输入两次密码，可分别切换可见性，创建成功后回到登录表单。
- **BREAKING** 注册和登录以原始大小写匹配账号名。完全同名时拒绝重复注册，`Alice` 和 `alice` 可以作为两个独立账号。
- 为注册设置独立限流和密码规则，沿用现有 CSRF、Argon2id 和数据库唯一索引。
- 切换时按开发测试数据重建方案处理旧测试账号、关联资料和 Redis 会话。

## Capabilities

### New Capabilities

### Modified Capabilities

- `user-auth` 增加自助注册与 Vue 注册流程，更新认证接口范围、账号精确匹配及限流规则。

## Impact

- `liveflow-core` 增加注册业务和账号写入，Flyway V3 修改账号列排序规则。
- `liveflow-infrastructure` 提供注册来源限流及剩余等待时间。
- `liveflow-api` 增加注册 HTTP 接口和错误映射，沿用当前安全过滤链。
- Vue 完成登录与注册切换、两次密码校验、密码显隐和错误提示，同一次变更交付后端与前端。
