## Why

`RoomKafkaRuntime` 同时管理发送、正式受理、三个输出消费组和维护任务。拆出各组件后，Kafka 客户端的拥有者、启停责任与故障处理路径可以独立核对。

## What Changes

- 将发送、Kafka Streams、正式输出消费组和维护调度拆为四个组件。
- 保留 `RoomKafkaRuntime` 统一校验业务 Topic，并协调启动、停机和失败后的资源清理。
- 将集成测试迁到新组件的实际入口。以三个实例统一运行新版验证发送、回执、历史和分区接管。

## Capabilities

### New Capabilities

### Modified Capabilities

## Impact

- 修改 `liveflow-infrastructure` 的 Kafka 运行时及对应后端测试，继续通过现有 `RoomMessagePorts.Producer` 接入业务。
- HTTP、WebSocket、Vue、MySQL、Redis 及 Kafka 事件沿用现有契约。部署复用 Topic、状态目录与消费组位点。Kafka 多 Broker 和副本扩容属于后续阶段。 [弹幕 V2 设计](../../../../docs/design/room-messages/v2/design.md#kafka-运行时拆分)
