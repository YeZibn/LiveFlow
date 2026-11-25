## Why

LiveFlow 的发送、回执和历史接口依赖本机持有完整 Kafka 分区。三个后端共同消费时，Kafka 正常分配任务也会导致接口误判为不可用。需要让三个对等实例共同提供服务，并在任一应用进程退出时保留消息进度和恢复能力。

## What Changes

- 同一 Spring Boot 应用部署三份，经同源代理接入，共享 Kafka、Redis 和 MySQL。每台实例独立持有 WebSocket 连接与 Streams 状态目录。
- 按目标提交分区的共享状态判断新预约能否接入。回执直接读共享 Redis，历史直接读共享 MySQL 快照，同时保留受影响范围的持久阻断。
- 为分区恢复、短时交接、持久故障及其接管定义独立处理规则。单个 Streams 客户端故障由本机隔离。
- 为回执重建和历史清理增加共享租约，补齐跨实例重连、代理摘流、滚动发布及回退操作。
- 增加三实例联调与故障演练，分别验证健康三台和失去一台后的容量。

## Capabilities

### New Capabilities

本次扩展弹幕能力的部署方式。

### Modified Capabilities

- `room-messages` 支持三实例接入、跨实例鉴权和实时分发、分区接管与读写降级。

## Impact

- 涉及 `liveflow-api` 的接口就绪与排空、`liveflow-core` 的发送预检和历史读取、`liveflow-infrastructure` 的 Kafka 与 Redis 适配、MySQL 迁移以及 Vue 房间页的重连调度。
- 沿用已有 HTTP 和 WebSocket 字段、Kafka Topic、分区数、Group ID 和排序契约。部署会新增实例标识与成员配置，并创建共享处理阻断表。
- 本轮范围为应用三实例。后续可按负载独立推进接入与后台部署、Redis 分片和 MySQL 历史分库。
