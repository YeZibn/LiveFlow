## Context

LiveFlow 在一个 JVM 中运行 MVC、WebSocket 和 Kafka 生产消费链路。发送业务会读 Redis 和 MySQL，再使用 Redis Lua 预约并调用 Kafka，Broker 确认后返回 `202`。Redis 保存冷却和查询绑定，Streams 生成正式消息与终态回执，MySQL 只由历史消费链路写入正式弹幕。变更动机见[提案](proposal.md)，对外行为见[弹幕规格差异](specs/room-messages/spec.md)。

## Goals / Non-Goals

**Goals**

- 给发送入口提供可验证的准入、回报和恢复边界，保护一次发送中的 MySQL、Redis 与 Kafka 调用。
- Vue、认证调用、Redis 预约和 Kafka 业务调用共同保持一次提交，写入结果与回执结果可区分。
- 用观察模式和故障演练验证高流量下的阈值，再启用对外拒绝。

**Non-Goals**

- 多实例共享熔断、独立网关和全站停流属于后续架构议题。本轮范围限定在发送 POST。
- 数据库和 Topic 继续使用 V2 的持久化模型，回执、历史和实时接收按各自依赖运行。

## Decisions

### 1. 在业务入口接入一个 Resilience4j 断路器

在 `liveflow-infrastructure` 的 `resilience` 实现接入 `resilience4j-circuitbreaker` 2.4.0，版本由父 POM 管理。`liveflow-core` 通过发送熔断接口使用其许可和回报。`RoomMessageService.send()` 在房间和提交标识及正文校验后、第一次发送 Redis 查询前取得许可，公开 API 安全链先完成 Bearer、当前会话和 CSRF 校验。程序化接入使发送业务可以处理异步 Kafka 结果以及免计样本。把熔断放在生产者内部会使 MySQL 与预约继续承压，因此生产者只保留现有就绪、停写和并发判定。 [主设计](../../../../docs/design/resilience/design.md#发送熔断)

| 调用阶段或结果 | 断路器动作 |
| --- | --- |
| OPEN 或半开许可用尽 | 在发送 I/O 前返回 `ROOM_MESSAGES_UNAVAILABLE`，拒绝指标与依赖失败指标分别记录 |
| 合格新发送获 Broker 确认 | `onSuccess`，从取得许可计时至真正确认 |
| 发送所需的 MySQL、Redis 或 Kafka 出现可识别依赖故障 | `onError`，保留内部阶段、原因与实际耗时 |
| 房间或会话无效、限流、冲突、原预约结果、主动停写、本机容量限制 | `releasePermission`，不产生结果样本 |

分类发生在错误仍保有来源的边界。发送适配器需要区分同样映射为 `503` 的就绪故障和本机拒绝，以及 `MESSAGE_SUBMISSION_UNKNOWN` 的实际生产失败与既有 `PENDING`。HTTP 等待超时和取消只影响响应。在 Broker 确认点上报成功，随后 Redis 写回使用原身份校验，写回失败单独计量。

### 2. 由 Resilience4j 管理窗口和半开状态

配置使用 `liveflow.resilience.circuit-breaker.room-messages`。默认 `observe` 对应 `METRICS_ONLY`，`enforce` 对应正常状态机。启动时校验比例、正值、开放初始和最大等待的关系，以及正常最小样本数至少能覆盖半开样本数。以已确认的配置作为首轮起点。

| 项目 | 起始配置 |
| --- | --- |
| 正常窗口 | `TIME_BASED` 10 秒，至少 30 个有效结果 |
| 故障条件 | 失败率 ≥20%，或慢调用率 ≥40%；耗时严格超过 800 毫秒计慢 |
| 暂停时间 | `waitIntervalFunctionInOpenState`，5、10、20、30 秒，30 秒封顶，恢复后重置 |
| 半开 | `automaticTransitionFromOpenToHalfOpenEnabled=false`，5 个许可，最多 60 秒，超时回 OPEN |

时间窗口能在不同负载下覆盖相同的故障观察时段；旧计数窗口的样本范围会随吞吐变化。半开从第一条通过校验的发送触发，使用真实请求。Resilience4j 按半开自身样本数计算相同阈值，以 5 个有效结果决定状态。中性结果归还许可，半开计时继续。 [参数、观测与回退](../../../../docs/design/resilience/deployment-and-operations.md#弹幕发送熔断配置) · [Resilience4j 指南](https://resilience4j.readme.io/docs/circuitbreaker)

库的异步回报入口取调用时的当前状态，业务请求必须带上准入时的轮次。适配器将准入、完成和归还的检查限制在短临界区，同时校验轮次及当前状态，包括库自身的半开超时。进入新的正常或半开轮次时更新轮次标识。每份许可只结算一次，旧轮次的确认仍处理其 Redis 写回和消息结果。数据库与网络访问在临界区外执行。 [状态机实现](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.4.0/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java)

### 3. 单次业务提交及可查询的结果

- 继续使用现有同槽预约和冷却键。Lua 只在绑定首次创建时授予一次 `leaseId`，原始 `PENDING` 或 `QUEUED` 分支只读。移除续领和发送租约时限，旧 `leaseUntilMs` 留在已有键中自然到期。确认脚本继续同时匹配 `submissionId` 和 `leaseId`，保留原 TTL。账号冷却由同一次 Lua 写入，并在预约后继续按绝对截止运行。 [Redis 预约](../../../../docs/design/room-messages/v2/storage.md#账号额度和提交绑定)
- Kafka 适配器每个有效预约最多调用一次 `send`，Producer 继续完成这次调用的协议交付。Streams、广播和历史消费者处理已有记录。连接中断和 HTTP 提前结束保留消息自身的生命周期。 [Kafka 生产契约](../../../../docs/design/room-messages/v2/event-contract.md#生产和确认)
- 在预约已知时把查询上下文带到 MVC 的异步等待边界，覆盖 Kafka 回调确认失败、重复待确认和 HTTP 等待超时。`503 MESSAGE_SUBMISSION_UNKNOWN` 仅在拥有当前作用域的预约信息时附带 `submissionId` 和 `receiptExpiresAt`。回执读取继续以当前账号、会话和房间为作用域。 [HTTP 错误](../../../../docs/design/room-messages/v2/api.md#http-错误)
- Vue 的发送 POST 用单次认证调用策略，其他调用沿用已有认证处理。`requestJson` 和发送客户端传递并校验可选回执信息。页面根据已入队、未发送及结果未确认显示反馈，结果未知时仅通过查询或已展示消息核对，明确未发送时保留草稿。 [发送交互](../../../../docs/design/room-messages/v2/api.md#发送交互)

## Risks / Trade-offs

- 全房间共用一个断路器会放大高流量局部故障的影响。只按有效发送样本裁决，并按阶段观测依赖失败和慢调用。健康峰值下的延迟分布用于校准 800 毫秒与 40%。
- 低流量下正常窗口可能达不到最小样本数。单次故障继续由接口报告；半开 60 秒允许多个账号陆续验证，样本未齐按退避继续暂停。
- HTTP 响应可能先于 Kafka 确认结束。预约保留原有效期，结果仍由 Kafka 和回执确定。未得到预约标识的页面通过实时列表或历史观察。
- 用有界阶段和结果标签记录状态切换、拒绝、依赖故障、慢调用、写回故障和回执未知。账号、房间和消息标识不作指标标签，访问凭证及正文不进入日志。

## Migration Plan

1. 暂停旧版发送 POST，退出仍能续领旧租约的后端。沿用原 Redis Key、MySQL 表、Kafka Topic 和消费位点，已有预约按旧绝对截止保留。
2. 成对发布后端和 Vue，确认 Kafka 正式接入就绪后重新开放发送。旧页面刷新加载新响应模型。首轮 `observe` 验证健康峰值、真实故障和半开规则，再启用 `enforce`。
3. 熔断策略异常时切回 `observe`，保留响应模型和单次提交。回退整个版本时先暂停发送，再成对切换后端与前端。 [发布与回退](../../../../docs/design/resilience/deployment-and-operations.md#发布与回退)
