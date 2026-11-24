## Why

LiveFlow 的弹幕目前依赖单进程内存完成受理、去重和广播，进程重启或观众断线后会丢失恢复所需的数据。V2 将提交和正式消息持久化，提供可查询的发送回执及按房间顺序恢复的历史，以支撑单实例高并发。

## What Changes

- **BREAKING** · 发送接口沿用路径与正文，成功状态从 `200` 改为 `202`，表示提交已获得 Kafka 确认。新增回执查询确认正式接收。
- 账号级发送限流和提交绑定在 Redis 原子裁决。Kafka 按房间处理提交，重复请求复用原结果，正式接收时为每条消息分配房间序号。
- **BREAKING** · WebSocket `message` 增加必填的 `roomSeq`，接收协议增加历史进度和重新同步通知。Redis Pub/Sub 负责实时转发，MySQL 保存可补拉的有序历史。
- Vue 支持待确认回执、同标识重试、首次进房读取历史、断线补拉及实时与历史合并。保留现有弹幕排版、输入法行为和草稿保护。
- 首轮用一个 LiveFlow 进程运行完整链路，纳入真实依赖联调、故障恢复和容量基线。

## Capabilities

### New Capabilities

### Modified Capabilities

- `room-messages` · 更新连接就绪、发送与去重、实时分发和前端恢复；新增发送回执查询、历史持久化与有序读取。

## Impact

- Java 涉及现有 MVC、安全规则、弹幕服务、WebSocket 接入、Redis 脚本、Kafka Producer 与 Streams、消费组及 MySQL 历史查询。
- MySQL 新增两张历史表和 Flyway 迁移。Kafka 增加提交、正式弹幕和回执 Topic，以及相应的 Streams 状态与消费组。
- Vue 与后端按 V2 契约一起切换。领域和协议边界详见 [弹幕 V2 完整设计](../../../docs/design/room-messages/v2/design.md)。
