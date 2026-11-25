## Why

当前一条迟到或冲突的弹幕可能阻塞整段历史消费，同分区其他房间的历史也随之停止推进。浏览器等待补齐连续序号时，已经收到的实时弹幕也会延迟展示。

## What Changes

- 浏览器立即展示可用弹幕，按房间序号保留最新 100 条。首次就绪、重连及收到恢复信号时查询最近历史，在窗口内合并迟到消息和持久记录。
- 历史允许跳号写入和迟到补写。幂等重放保留原记录，已确认的冲突仅跳过本条并记录诊断信息。数据库失败保留 Kafka 位置，持久分区故障继续按受影响分区阻断。
- **BREAKING** 历史 GET 返回 `latestStoredSeq`、`throughSeq` 和 `nextAfterSeq`，替换原连续水位与目标字段。旧游标可读取当前可用记录，移除 `ROOM_HISTORY_GAP`。WebSocket 的 `history_progress` 改为使用 `latestStoredSeq`。
- MySQL 保存房间最大落库序号，保留规则按正式受理时间计算。通过 Flyway 就地迁移，三实例后端和 Vue 协调切换，从原消费位置接续。

## Capabilities

### New Capabilities

### Modified Capabilities

- `room-messages`：更新实时列表、历史写入和查询、进度通知、分区接管及跨实例查房后的历史读取规则。

## Impact

- 后端涉及 `liveflow-core` 的历史查询、写入和清理，`liveflow-infrastructure` 的历史 Kafka 消费与确认，以及 `liveflow-api` 的 HTTP 与 WebSocket 契约。
- Vue 的历史客户端和房间弹幕状态需要同步升级。现有弹幕发送、回执、账号额度与熔断仍使用既有契约。
- 发布涉及历史表和清理索引迁移、旧历史冲突记录核验、三实例协调恢复。沿用现有 Kafka Topic、分区、消费组、Streams 状态及 Redis 房间频道。 [弹幕 V2 设计](../../../../docs/design/room-messages/v2/design.md)
