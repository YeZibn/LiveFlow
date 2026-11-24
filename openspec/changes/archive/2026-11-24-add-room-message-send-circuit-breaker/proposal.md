## Why

弹幕发送在依赖故障期间仍会查询房间、访问 Redis 并尝试入队，高流量会放大失败和等待。发送结果不确定时，现有页面、认证封装和 Redis 续领路径都可能触发再次提交，增加用户判断与故障恢复的复杂度。

## What Changes

- 在单实例的弹幕发送业务入口引入一个 Resilience4j `CircuitBreaker`，覆盖 MySQL 查询、Redis 预约和 Kafka 入队。按有效样本的失败率与慢调用率熔断，由用户主动发送的新弹幕验证恢复。
- 前后端统一采用单次提交。Redis 原子授予一次发布许可，重复提交按原绑定返回已知结果、待确认或正文冲突。取消租约到期后的重新发布许可与页面「重试原弹幕」操作。
- **BREAKING** 发送成功的 `202` 移除 `retryUntil`。`MESSAGE_SUBMISSION_UNKNOWN` 在预约已知时返回回执标识与截止时间，页面通过 GET 查询发送结果。
- 分开观察入口熔断、业务限流、依赖失败、慢调用和迟到回报。首轮先采集指标，再启用熔断拒绝。

## Capabilities

### New Capabilities

### Modified Capabilities

- `room-messages` — 新增发送熔断与半开验证规则，更新提交、重复请求、房间面板和回执交互。

## Impact

- `liveflow-core` 增加发送熔断接口及业务编排，`liveflow-infrastructure` 接入 `resilience4j-circuitbreaker`、调整 Redis Lua 和生产错误分类，`liveflow-api` 更新发送响应及配置绑定。
- Vue 更新发送、认证调用方式和反馈。送达结果由既有回执及实时或历史列表呈现。
- 发布时成对切换后端和 Vue。现有 MySQL 表、Kafka 事件格式与保留策略继续承载历史和回执。熔断状态存于 JVM，预约与账号冷却使用现有 Redis 键及绝对有效期。

[整体设计](../../../../docs/design/resilience/design.md) · [发送 API](../../../../docs/design/room-messages/v2/api.md#发送弹幕) · [运行参数](../../../../docs/design/resilience/deployment-and-operations.md#弹幕发送熔断配置)
