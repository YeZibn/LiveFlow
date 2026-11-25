# 直播间弹幕 V2 MySQL 数据库设计

## 数据归属与访问场景

历史消费组将 Kafka 正式事件写入 MySQL。历史接口读同一房间的最近一段，或从客户端已有连续序号补读，均按 `room_seq` 定位与排序。另存每个房间已连续写入的水位与最早可恢复位置，便于判断积压和历史清理。房间和发送者资料依照现有模块归属，历史保留消息受理时的显示名快照。 [事件契约](event-contract.md#正式弹幕事件) · [历史接口](api.md#查询历史) · [房间数据库](../../room/database.md)

### 发送熔断的数据边界

发送获得断路器许可后才查询房间及发送者资料。发送 POST 与 MySQL 历史写入分别发生在 HTTP 发送和 Kafka 历史消费阶段。MySQL 保存正式弹幕和连续落库水位，Redis 保存发送许可，熔断状态由 JVM 持有。此次熔断使用下文的既有表和索引，无需 DDL、回填或 Flyway 迁移。 [发送熔断与回报规则](../../resilience/design.md#发送熔断) · [Redis 预约](storage.md#账号额度和提交绑定)

## 表和索引

### `room_messages`

| 字段 | 类型 | 约束与用途 |
| --- | --- | --- |
| `room_id` | `CHAR(36)`，ASCII 二进制排序 | 非空，房间公开 UUID |
| `room_seq` | `BIGINT` | 非空，房间内从 1 递增，最大值预留一个位置供历史清理水位使用 |
| `message_id` | `CHAR(36)`，ASCII 二进制排序 | 非空，正式弹幕的服务端 UUID，全局唯一 |
| `sender_user_id` | `BIGINT` | 非空，可信发送账号 ID，用于内部追溯 |
| `sender_name` | `VARCHAR(32)`，utf8mb4 | 非空，显示名快照，容纳现有昵称与账号名 |
| `content` | `VARCHAR(100)`，utf8mb4 | 非空，规范化的纯文本 |
| `sent_at` | `DATETIME(3)` | 非空，正式受理时间，按 UTC 读写 |
| `stored_at` | `DATETIME(3)` | 非空，首次写入历史的 UTC 时间，用于保留策略，重复消费保持原值 |

主键 `(room_id, room_seq)` 用于房间范围扫描、最近消息和顺序校验。`uk_room_messages_message_id` 裁决同一消息 ID 的重复写入。`idx_room_messages_stored_at` 用于发现需要清理的候选房间。历史独立保留，读取入口按现有房间与会话权限校验。

### `room_message_history_state`

| 字段 | 类型 | 约束与用途 |
| --- | --- | --- |
| `room_id` | `CHAR(36)`，ASCII 二进制排序 | 主键，与历史房间标识同型 |
| `stored_through` | `BIGINT` | 非空，已连续写入至该序号，初值 0 |
| `earliest_seq` | `BIGINT` | 非空，最早可读取的序号，初值 1。全部已清理时为 `stored_through + 1` |
| `updated_at` | `DATETIME(3)` | 非空，本房间历史进度最后变化的 UTC 时间 |

## DDL

```sql
CREATE TABLE room_message_history_state (
    room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stored_through BIGINT NOT NULL,
    earliest_seq BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (room_id),
    CONSTRAINT ck_room_message_history_range
        CHECK (stored_through >= 0 AND earliest_seq > 0 AND earliest_seq - 1 <= stored_through)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_messages (
    room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    room_seq BIGINT NOT NULL,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sender_user_id BIGINT NOT NULL,
    sender_name VARCHAR(32) NOT NULL,
    content VARCHAR(100) NOT NULL,
    sent_at DATETIME(3) NOT NULL,
    stored_at DATETIME(3) NOT NULL,
    PRIMARY KEY (room_id, room_seq),
    UNIQUE KEY uk_room_messages_message_id (message_id),
    KEY idx_room_messages_stored_at (stored_at, room_id, room_seq),
    CONSTRAINT ck_room_messages_sequence CHECK (room_seq BETWEEN 1 AND 9223372036854775806)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 历史写入和水位

1. 历史组按单分区内的 offset 顺序消费。一个数据库事务处理一批来自同一分区的记录。按房间 UUID 固定顺序锁定各房间状态行；新房间按初值创建状态行。不同分区可并行提交。
2. 新事件的序号必须等于该房间当前 `stored_through + 1`。数据库一次写入该批新记录并更新各房间水位。同一批中相同房间的后续事件沿用已推进的事务内水位。
3. 序号低于 `earliest_seq` 时视为已过历史保留期的重放。序号处于已落库且保留区间时，比较原事件的消息 ID、发送者和正文等不可变字段。完全相同才确认幂等。遇到新事件跳号，或同一序号、同一消息 ID 对应不同数据时，回滚该批并暂停分区。通过唯一索引和显式冲突比较处理，不使用会吞掉其他写入错误的批量忽略语义。
4. 数据与水位在同一事务提交后，才向 Kafka 提交到下一条的 offset。数据库失败、内容冲突或提交结果不明确时保留消费位置。重读时按保留区间和事件标识裁决。 [历史消费确认](event-contract.md#三个输出消费组)

## 处理阻断记录

三实例新增共享的 `room_message_processing_blocks`。记录仅用于必须修复后才能恢复的处理错误。正常的分区交接、积压和临时依赖异常由运行状态与监控反映。Kafka 消费组与 Topic 名使用包含实际命名空间的完整名称。

| 字段 | 定义 |
| --- | --- |
| `consumer_group`、`topic_name`、`partition_no` | 唯一定位职责组及受影响 Kafka 分区。 |
| `room_id` | 分区级记录用空字符串。房间级记录用规范房间 UUID。房间序号冲突可单独保存房间记录。 |
| `reason` | `OFFSET_OUT_OF_RANGE` 表示已提交位置落在当前可读日志范围之外，`HISTORY_SEQUENCE_CONFLICT` 表示历史缺口或不可变数据冲突，`PROCESSING_ERROR` 表示已定位到分区且需修复的数据处理错误。 |
| `observed_offset` | 观察到的故障位置。无可确认值时允许为空。 |
| `reported_by`、`updated_at` | 最近报告的实例标识与 UTC 时间，用于接管和修复定位。 |
| `revision` | 记录版本。故障记录更新时递增，用于清除前校验。 |

```sql
CREATE TABLE room_message_processing_blocks (
    consumer_group VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_name VARCHAR(249) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_no INT NOT NULL,
    room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
    reason VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_offset BIGINT NULL,
    reported_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    PRIMARY KEY (consumer_group, topic_name, partition_no, room_id)
) ENGINE=InnoDB;
```

1. 消费者发现持久故障后保留已提交 offset。历史批次先回滚，再用独立事务写阻断记录，避免记录随历史事务回滚。阻断记录写入失败时保持本地暂停并告警。接管时先确认共享阻断状态，再开放该分区处理。
2. 历史数据冲突暂停整个消费分区的推进。读侧用房间级记录屏蔽已确认数据冲突的房间。分区级历史位点越界屏蔽该分区的历史查询。广播、回执和正式接收的记录分别按自己的职责范围生效。
3. 修复时按记录核对仍可重放的 Kafka 位点、历史数据与连续水位，经确认后清除对应记录并恢复消费。清除时核对记录仍是本次已核验的版本。正常重启、分区转移和短期状态过期均保留阻断记录。 [分区交接](event-contract.md#三实例分区交接与接入判断) · [故障处置](deployment-and-operations.md#故障处置)

## 历史查询和分页

历史状态和消息页从 MySQL 主库的同一一致性快照读取。没有状态行时返回 `earliestSeq=1` 和 `storedThrough=0`。历史请求以 UUID 和序号范围走主键，返回公开字段。

查询同时读取历史消费组中目标分区与目标房间的阻断记录。存在有效记录时返回不可用；正常交接和落库延迟时继续返回本次快照内的可读页。WebSocket 水位通知按相同房间与分区规则跳过受阻范围。 [阻断记录](#处理阻断记录) · [历史接口](api.md#查询历史)

```sql
-- 新进房读取最近一段。查询倒序返回，接口在内存反转成升序。
SELECT room_seq, message_id, sender_name, content, sent_at
FROM room_messages
WHERE room_id = :roomId AND room_seq <= :storedThrough
ORDER BY room_seq DESC
LIMIT :limit;

-- 按序补拉。可读上限取连续落库水位与本轮目标中较小者。
SELECT room_seq, message_id, sender_name, content, sent_at
FROM room_messages
WHERE room_id = :roomId AND room_seq > :afterSeq AND room_seq <= :readThrough
ORDER BY room_seq ASC
LIMIT :fetchSize;
```

补拉读取 `limit+1` 行判断当下是否还有可读下一页。历史裁剪已超过客户端游标时返回范围过期。暂时读到水位而尚未达到本轮目标时，客户端在已返回最后一个序号继续等待。 [历史接口字段](api.md#查询历史)

## 保留和迁移

首轮以 **历史写入后保留 24 小时**作为运行起点。清理任务按候选房间锁定状态行，只删除达到保留期的连续前缀，每批上限 500 行，并在同一事务推进 `earliest_seq`。清理不回退 `stored_through`。低流量房间也会通过清理索引被发现。

通过新增 Flyway 迁移创建两张表，从 V2 切换后第一条正式弹幕开始写入，V1 的进程内消息没有历史来源。生产以 InnoDB 事务、适当的日志刷盘和备份策略保护历史。恢复历史从可信水位和 Kafka 可重放边界开始，恢复与保留关系见运行设计。 [生产和恢复](deployment-and-operations.md#故障处置) · [InnoDB 持久化](https://dev.mysql.com/doc/refman/8.4/en/mysql-acid.html)
