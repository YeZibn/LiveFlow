## Context

每个 LiveFlow 应用进程内运行一套弹幕 Kafka 组件。发送业务依赖 `RoomMessagePorts.Producer`，Streams 分区状态通过 `RoomAdmissionStatus` 共享，正式输出组各自确认外部副作用。当前类统一持有这些客户端和维护任务。 [变更提案](proposal.md) · [弹幕 V2 设计](../../../../docs/design/room-messages/v2/design.md#kafka-运行时拆分)

## Goals / Non-Goals

- **目标**：明确每个客户端的创建、关闭与失败处理责任。保留一个启动顺序和清理入口，供三个同版实例共同采用。
- **范围**：沿用现有 API、WebSocket、Vue、MySQL 表与迁移版本、Redis Key 与租约、Kafka Topic 与分区、消费组和 Streams 状态。Kafka 高可用部署留待后续阶段。

## Decisions

| 组件 | 所有权和接口边界 |
| --- | --- |
| `RoomKafkaProducer` | 作为唯一注入的发送端口实现，持有提交客户端和本机生产名额。沿用 `requireAdmission(roomId)`、`publish(submission)`、原配置和错误映射。准入依赖共享的 `RoomAdmissionStatus`。 |
| `RoomKafkaStreamsRuntime` | 持有 Streams 客户端及探测客户端。组装既有正式受理拓扑，管理状态目录、分区观测和退出快照。保留现有任务异常策略。 |
| `RoomKafkaConsumerGroupRuntime` | 创建和关闭广播、历史、回执三个正式消费组。复用 `RoomKafkaConsumer` 的确认、暂停和重读机制。历史事务成功后才允许推进位点，各组指标按原口径记录。 |
| `RoomKafkaMaintenanceScheduler` | 管理回执重建和历史清理的调度与租约。每次回执重建临时创建消费者，回放本轮范围，并由同一任务关闭客户端。正式回执位点由正式组推进。 |
| `RoomKafkaRuntime` | 唯一的 Kafka `SmartLifecycle` 协调器。保留 Topic 创建与校验，统一启动和关闭组件。 |

由一个协调器管理生命周期，可以在 Topic 校验失败或部分客户端启动失败时执行统一清理，也能保留 HTTP 与 WebSocket 排空后关闭 Kafka 的顺序。共享设置沿用现有配置键、默认值、校验和命名逻辑。每类指标由所属组件注册一次。

启动先校验 Topic，再准备 Producer 和 Streams，启动正式消费组后开始 Streams 处理，最后启用维护调度。完成组件启动后按目标分区准入。单独停写时后台继续处理已入队提交。正常停机先撤下本机准入并停止启动新维护任务，在途维护在批次边界检查状态和租约。随后关闭 Producer、Streams 和正式消费组。部分启动失败时关闭已有资源并保留原错误，单个关闭出错时继续关闭其余组件。 [启停与发布要求](../../../../docs/design/room-messages/v2/deployment-and-operations.md#kafka-运行时拆分切换)

## Risks / Trade-offs

- **发送名额提前释放** → 名额仍随 Kafka 调用完成归还，同步异常与异步回调共用一个完成路径。HTTP 等待提前结束时继续等待原 Kafka 结果。
- **分区退出被迟到采样覆盖** → 停止采样和撤下实例快照需有明确顺序，保留已有快照新鲜度和交接宽限。
- **消费组确认边界发生变化** → 广播、历史、回执分别确认自己的外部副作用。历史异常保留原位点，单条已确认冲突按原策略跳过。
- **维护任务与停机竞争** → 保持租约校验和批次停止条件，临时客户端由发起任务关闭。恢复标记只能由仍持有租约的任务提交。

## Migration Plan

摘除原实例入口并排空，待原进程全部退出后，使用既有实例标识、状态目录和消费位点启动三个相同新版。检查跨实例发送、实时分发、回执、历史和接管后开放流量。回退时摘流并关闭整套新版，再启动原构建，复用已有数据和位点。 [切换与新版验证](../../../../docs/design/room-messages/v2/deployment-and-operations.md#kafka-运行时拆分切换)
