## Why

三实例基线中，300 次访问同一个热门房间或同一个不存在的 UUID，分别执行了 300 条房间存在性 SQL。Ticket、兑票、发送和历史应复用已确认的房间结果，并在大量不同 UUID 或缓存故障时限制这条查询对 MySQL 的压力。 [基线与对照场景](../../../../docs/design/room/storage.md#基线与对照场景)

## What Changes

- 三台后端共用独立 Redis 房间存在性缓存，保存有期限的正负结果，按房间合并并发回填。
- 统一约束未命中、缓存故障和旁路时的 MySQL 回源。仅在能确认房间不存在时返回 `404 ROOM_NOT_FOUND`，超出回源预算或无法确认时使用现有暂不可用响应。
- 创建房间成功后预热正缓存。已提交的创建结果可在缓存异常时正常返回，旧回填受租约校验和负缓存截止时间约束。
- 补充逐台启用、旁路回退及可与原三实例基线直接比较的 SQL 统计。

## Capabilities

### New Capabilities

### Modified Capabilities

- `room-creation` 明确创建并提交后的房间在存在性检查中的可见性，以及缓存预热失败时的创建响应。
- `room-messages` 统一 Ticket、兑票、新发送、历史的房间存在性判定，定义跨实例回填、受控回源与失败语义。

## Impact

- `liveflow-core` 复用存在性查询并接入创建后预热。 `liveflow-infrastructure` 提供缓存 Redis、Lua、回源保护和观测。`liveflow-api` 负责配置及既有 HTTP 和 WebSocket 错误映射。
- 本地 Compose 增加独立缓存服务。业务 Redis 继续承载会话、Ticket、发送状态和广播，MySQL 沿用既有表和索引。
- 房间详情、目录、本人房间、发送者资料和历史快照继续按原路径读取。Vue 沿用现有接口、连接恢复和发送状态处理。 [房间设计](../../../../docs/design/room/design.md#房间存在性缓存)
