## Why

认证限流和会话仓储耦合，弹幕使用每账号 10 秒最多 5 条的旧规则。需要独立管理单实例的限流策略，并用 10 秒间隔控制同一账号的新弹幕提交。

## What Changes

- 在现有后端模块中建立 `resilience` 边界，迁出登录与注册的 Redis Lua 固定窗口裁决，保留原额度和计数。
- 登录超限响应增加整数秒 `Retry-After`，登录和注册页面按服务端等待时间提示。
- **BREAKING** 弹幕新预约改为按账号共享的 10 秒冷却。Lua 同时裁决冷却、原标识重试与预约，前端按 `Retry-After` 提示并保留草稿。
- 切换时直接清理本环境旧弹幕额度 ZSET。保留认证计数、提交预约、回执和历史，沿用现有 Kafka 与 MySQL 链路。

## Capabilities

### New Capabilities

本次由现有认证和弹幕能力维护各自的可观察规则。

### Modified Capabilities

- `user-auth` 定义登录双维度固定窗口的原子扣额、等待时间与登录反馈。
- `room-messages` 定义账号冷却、原标识重试以及房间面板的倒计时行为。

## Impact

涉及认证服务与会话接口、Redis Lua 与配置、弹幕预约、MVC 错误响应、Vue 登录及弹幕面板，以及认证与弹幕 V2 文档。计划包含受控环境的旧 Redis 额度清理和故障回归。MySQL 表与索引沿用现有结构。范围以[已确认的单实例限流设计](../../../docs/design/resilience/design.md)为准。
