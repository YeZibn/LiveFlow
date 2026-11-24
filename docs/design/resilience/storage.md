# 单实例限流存储设计

Redis 是限流裁决和到期时间的权威来源。MySQL 提供认证账号及其稳定 ID。 [模块设计](design.md) · [弹幕预约与回执](../room-messages/v2/storage.md)

## Redis Key 和生命周期

| Key | 类型和值 | 到期规则 |
| --- | --- | --- |
| `liveflow:auth:{login}:account:<username>` | String，账号登录尝试次数 | 该账号窗口内首次计数后 60 秒 |
| `liveflow:auth:{login}:source:<ip>` | String，来源登录尝试次数 | 该来源窗口内首次计数后 60 秒 |
| `liveflow:auth:registration:source:<ip>` | String，来源注册尝试次数 | 窗口内首次计数后默认 600 秒 |
| `<env>:liveflow:rooms:message:{<userId>}:cooldown` | String，开启冷却的预约 `submissionId` | 从 Redis 首次许可时间起默认 10,000 毫秒 |

认证计数沿用原 Key，用户名按原拼写参与构造，来源取可信连接地址。登录两个 Key 共用 `{login}` 标签。弹幕冷却键与现有预约键 `<env>:liveflow:rooms:message:{<userId>}:submit:<sessionId>:<roomId>:<clientMessageId>` 共用账号标签。会话和房间参与标识预约，账号决定冷却归属。

## Lua 裁决

### 登录与注册固定窗口

- 登录脚本读取两个计数和剩余 TTL。只要任一维度达到上限即拒绝，并返回所有达到上限维度中最长的剩余毫秒。低于上限的维度本次也不扣额。
- 两个维度都通过时共同递增。每个键在该维度首次计数时设置 TTL，后续递增沿用原截止时间。注册脚本只裁决注册来源键，使用相同的固定窗口规则。
- 脚本结果区分允许、额度耗尽和状态异常。存在的计数若缺少 TTL，交由应用映射为服务不可用。对超限窗口临界值，HTTP 层按最少一秒返回等待提示。

### 弹幕冷却与预约

一个脚本接收账号冷却键、预约键、当前候选预约和现有租约相关参数。先检查预约绑定的正文摘要、状态、重试截止与租约，再处理新预约。对新预约读取冷却绝对截止。当前 Redis 毫秒小于截止时返回剩余毫秒，不修改任何键。当前毫秒等于截止时即可放行。存在的冷却键缺少有效到期时间时按依赖故障处理。

冷却结束后，脚本读取 Redis 时间并为新预约保存首次许可时间。同一次 Lua 执行内写入冷却键和预约，冷却截止为首次许可时间加配置间隔，预约使用自己的保留期限。这样同账号的两个并发新请求无法同时获得许可。脚本在写入前检查参数和读取状态，Redis 异常沿用依赖故障语义。

现有预约、租约、回执继续按弹幕 V2 契约维护。重试期限与冷却分别计算，Broker 写入确认和正式接收结果继续由既有链路确认。

## MySQL 表和索引

本轮无需 SQL DDL、数据回填或 Flyway 迁移。弹幕主体继续引用 `users.id`，登录以原始用户名读取账号，`uk_users_username` 和 `ascii_bin` 继续约束精确拼写唯一性。历史表 `room_messages` 和 `room_message_history_state` 继续由弹幕 V2 写入和读取。 [认证数据库](../auth/database.md) · [弹幕历史数据库](../room-messages/v2/database.md)
