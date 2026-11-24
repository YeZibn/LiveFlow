# 直播间弹幕 V2 消息队列设计

## Topic 和消费组

| Topic | Key | 生产者与消费者 | 数据生命周期 |
| --- | --- | --- | --- |
| `liveflow.room-message.submissions.v1` | `roomId` | MVC 发送服务写入，Streams 正式接收流程消费 | 初始保留 72 小时，按时间清理 |
| `liveflow.room-message.accepted.v1` | `roomId` | Streams 写入，广播组、历史组各自独立消费 | 初始保留 72 小时，按时间清理 |
| `liveflow.room-message.receipts.v1` | `submissionId` | Streams 写入，回执投影组消费 | `compact,delete`，初始保留 72 小时，Redis 查询期以事件截止时间为准 |

Streams 应用 ID 为 `liveflow-room-message-admission-v1`。广播、历史、回执的固定 Group ID 分别为 `liveflow-room-message-broadcast-v1`、`liveflow-room-message-history-v1`、`liveflow-room-message-receipt-v1`。三组独立推进进度，部署更多实例时同职责继续使用相同 Group ID。

三类 Topic 首轮各用 6 个分区。提交与正式弹幕沿用相同分区算法及分区数，以稳定的小写房间 UUID UTF-8 字节作为键。Streams 状态依赖提交 Topic 分区。切换分区数时要停止写入并追平旧 Topic，再迁移房间末序号及仍有效的去重、回执状态；随后开启新版本 Topic，后续房间序号连续推进。 [运行参数](deployment-and-operations.md#初始参数)

## 事件格式

Kafka Value 采用 UTF-8 JSON，字段名使用小驼峰。时间为三位小数秒的 UTC ISO 8601。账号 ID 与房间序号使用十进制字符串，UUID 使用标准小写格式。各 Topic 的 `schemaVersion` 为整数 `1`。可选字段可增量添加，改变字段语义或分区规则时升级 Topic 契约。

### 提交命令

| 字段 | 语义 |
| --- | --- |
| `schemaVersion` | 事件结构版本 |
| `submissionId` | Redis 首次允许入队时登记的预约 UUID，也是回执关联标识 |
| `roomId`、`userId`、`sessionId` | 经 HTTP 校验后的目标房间和可信身份 |
| `clientMessageId` | 客户端原始提交 UUID |
| `content`、`contentHash` | 规范化正文及其 UTF-8 SHA-256 小写十六进制摘要 |
| `senderName` | HTTP 接入时取得的发送者显示名快照 |
| `admittedAt` | 首次获得 Redis 入队许可的时间，同一次预约保持一致 |

从 `admittedAt` 起，新的正式受理截止为 **12 分钟**，回执可查截止为 **30 分钟**。消费者遇到已经超过查询截止的命令时，推进处理进度并记录过期指标。 [时间窗口和键生命周期](storage.md#账号额度和提交绑定)

```json
{
  "schemaVersion": 1,
  "submissionId": "4154ce3d-d846-4bd5-8fa9-a79b156a11ed",
  "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
  "userId": "42",
  "sessionId": "4f71a8a3-c6ce-4c0c-b059-6a0951765866",
  "clientMessageId": "1c3d6dfa-58a1-42a3-9141-478c35dad5bf",
  "content": "LiveFlow",
  "contentHash": "6dc84ef603987b9dc49ee9cb2d2a245301c962b43ab24b1f93004b0dd870547e",
  "senderName": "小叶",
  "admittedAt": "2026-11-24T08:30:00.000Z"
}
```

### 正式弹幕事件

| 字段 | 语义 |
| --- | --- |
| `schemaVersion` | 事件结构版本 |
| `roomId`、`roomSeq` | 房间和从 1 递增的正式顺序，消息排序以此为准 |
| `messageId` | 首次正式接收时生成的 UUID，消费重放时沿用原值 |
| `userId` | 仅供内部持久化的发送账号 |
| `senderName`、`content` | 正式接收的显示名快照和纯文本正文 |
| `sentAt` | 首次正式接收的 UTC 时间 |

```json
{
  "schemaVersion": 1,
  "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
  "roomSeq": "41",
  "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
  "userId": "42",
  "senderName": "小叶",
  "content": "LiveFlow",
  "sentAt": "2026-11-24T08:30:00.123Z"
}
```

### 终态回执事件

通用字段为 `schemaVersion`、`submissionId`、`roomId`、`userId`、`sessionId`、`status`、`expiresAt`。成功状态为 `accepted`，附带 `messageId`、`roomSeq`、`sentAt`；拒绝状态为 `rejected`，附带 `code`，取值为 `MESSAGE_ID_CONFLICT` 或 `MESSAGE_SUBMISSION_EXPIRED`。查询接口按请求身份读取投影并转换为公开回执。 [回执查询](api.md#查询提交回执)

```json
{
  "schemaVersion": 1,
  "submissionId": "4154ce3d-d846-4bd5-8fa9-a79b156a11ed",
  "roomId": "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c",
  "userId": "42",
  "sessionId": "4f71a8a3-c6ce-4c0c-b059-6a0951765866",
  "status": "accepted",
  "messageId": "967b8ca4-0d7d-4656-b0aa-3dfaec51589e",
  "roomSeq": "41",
  "sentAt": "2026-11-24T08:30:00.123Z",
  "expiresAt": "2026-11-24T09:00:00.000Z"
}
```

## 生产和确认

每个获准的预约最多调用一次 `producer.send()`。Producer 启用 `enable.idempotence=true` 和 `acks=all`，在本次调用的交付期限内执行协议层交付。应用收到 Broker 确认后才返回 `202`，并尝试将预约标为已入队。无法确认结果时返回 `MESSAGE_SUBMISSION_UNKNOWN`，通过回执查询已知预约的结果。Streams 负责业务去重。

生产环境业务 Topic 使用 3 副本、`min.insync.replicas=2` 和 `unclean.leader.election.enable=false`。事务日志、消费进度与 Streams changelog 按同等可靠性要求配置。 [Kafka Producer 配置](https://kafka.apache.org/43/configuration/producer-configs/) · [Kafka Topic 配置](https://kafka.apache.org/43/configuration/topic-configs/)

## 正式接收和顺序

1. 同一提交分区由一个 Streams task 按 offset 顺序处理。业务去重键为 `sessionId + roomId + clientMessageId`，保存首次正式接收的正文摘要和回执。房间末序号、业务去重、按 `submissionId` 的终态分别存入有 changelog 的状态存储。房间末序号在房间生命周期内保留。接收逻辑验证负载与 Topic 键中的房间一致，再操作房间状态。
2. 先裁决同一 `submissionId` 已有的终态。该终态不可被重放覆盖。新预约命中业务去重时，相同正文生成指向原弹幕的回执，异文生成冲突回执。无历史记录且超过 12 分钟接收期限的命令生成过期回执。符合接收条件的首条命令分配下一房间序号，发布一次正式事件。
3. 房间末序号、判重与终态变更、正式事件、回执事件和输入进度使用 `exactly_once_v2` 一并提交。两个输出 Topic 的消费组使用 `read_committed`。业务去重状态自正式接收起保留 30 分钟，预约终态保留到该预约的查询截止。进程重启或任务迁移时先从 changelog 恢复，再继续处理。
4. `roomSeq` 为各房间独立的业务序号，按 Broker 追加顺序分配。展示时间只用于阅读。重复、冲突和逾期命令均不占用序号。不同房间各自编号。 [Kafka Streams 状态与事务](https://kafka.apache.org/43/streams/core-concepts/#processing-guarantees) · [Kafka 事务隔离](https://kafka.apache.org/43/design/design/#using-transactions)

## 三个输出消费组

| 消费组 | 确认点 | 失败和重放 |
| --- | --- | --- |
| 广播 | 按同一分区顺序执行 Redis 发布，收到结果后提交连续的下一 offset。返回 0 也属于已完成发布。 | 调用失败或结果不确定时保留未确认位置，退避后重试。长时间故障暂停该分区并维持消费者心跳。超过实时新鲜窗口的旧事件可按运行规则推进广播进度，由历史补拉覆盖。 [运行故障处置](deployment-and-operations.md#故障处置) |
| 历史 | 同一分区按序批写，MySQL 提交后才提交该批的下一 offset。 | 数据库失败保留原位置。相同事件重放按数据库约束确认，序号缺口或数据冲突暂停分区。 [历史事务](database.md#历史写入和水位) |
| 回执投影 | Redis 已保存不可变终态，或终态已到查询截止后，才提交该分区的连续进度。 | Redis 失败时保留位置退避重试。投影可从 Kafka 回执 Topic 重建，旧记录遵守事件绝对截止时间。 [Redis 回执投影](storage.md#回执投影) |

消费者关闭自动提交。遇到无法解码或版本不受支持的持久化事件时暂停对应分区，记录 Topic、分区、offset 和版本，修复后从原位置恢复。Kafka 保留期之外的进度丢失需要通过明确的历史恢复流程处理。涉及 Redis 和 MySQL 的副作用分别按各自确认边界处理。
