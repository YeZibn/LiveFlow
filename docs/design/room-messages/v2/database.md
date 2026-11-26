# 直播间弹幕 V2 MySQL 数据库设计

## 数据归属与访问场景

历史消费组将 Kafka 正式事件写入 MySQL。历史接口支持最近一页、按序号向前回看和按范围补拉，均按同一房间的 `room_seq` 定位与排序。房间历史状态保存已经成功落库的最大序号，序号之间允许有缺口。房间和发送者资料依照现有模块归属，历史保留消息受理时的显示名快照。 [事件契约](event-contract.md#正式弹幕事件) · [历史接口](api.md#查询历史) · [房间数据库](../../room/database.md)

### 发送熔断的数据边界

发送获得断路器许可后才查询房间及发送者资料。发送 POST 与 MySQL 历史写入分别发生在 HTTP 发送和 Kafka 历史消费阶段。MySQL 保存正式弹幕和落库进度，Redis 保存发送许可，熔断状态由 JVM 持有。 [发送熔断与回报规则](../../resilience/design.md#发送熔断) · [Redis 预约](storage.md#账号额度和提交绑定)

## 表和索引

### `room_messages`

| 字段 | 类型 | 约束与用途 |
| --- | --- | --- |
| `room_id` | `CHAR(36)`，ASCII 二进制排序 | 非空，房间公开 UUID |
| `room_seq` | `BIGINT` | 非空，沿用正式事件的序号范围 1–9223372036854775806 |
| `message_id` | `CHAR(36)`，ASCII 二进制排序 | 非空，正式弹幕的服务端 UUID，全局唯一 |
| `sender_user_id` | `BIGINT` | 非空，可信发送账号 ID，用于内部追溯 |
| `sender_name` | `VARCHAR(32)`，utf8mb4 | 非空，显示名快照，容纳现有昵称与账号名 |
| `content` | `VARCHAR(100)`，utf8mb4 | 非空，规范化的纯文本 |
| `sent_at` | `DATETIME(3)` | 非空，正式受理时间，按 UTC 读写，是历史保留期限的起点 |
| `stored_at` | `DATETIME(3)` | 非空，首次写入历史的 UTC 时间，用于衡量落库延迟，重复消费保持原值 |

主键 `(room_id, room_seq)` 用于房间历史查询并约束同一序号只有一条记录。`uk_room_messages_message_id` 约束消息 ID 全局唯一。`idx_room_messages_sent_at` 按受理时间查找清理候选行。历史独立保留，读取入口按现有房间与会话权限校验。

### `room_message_history_state`

| 字段 | 类型 | 约束与用途 |
| --- | --- | --- |
| `room_id` | `CHAR(36)`，ASCII 二进制排序 | 主键，与历史房间标识同型 |
| `latest_stored_seq` | `BIGINT` | 非空，范围 0–9223372036854775806，初值 0。记录房间已成功写入历史的最大序号，迟到写入和清理均不回退 |
| `updated_at` | `DATETIME(3)` | 非空，房间历史状态创建或最大序号上升的 UTC 时间 |

## DDL

以下为目标表结构，已有环境通过追加 Flyway 迁移就地调整。 [迁移规则](#保留和迁移)

```sql
CREATE TABLE room_message_history_state (
    room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    latest_stored_seq BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (room_id),
    CONSTRAINT ck_room_message_history_max
        CHECK (latest_stored_seq BETWEEN 0 AND 9223372036854775806)
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
    KEY idx_room_messages_sent_at (sent_at, room_id, room_seq),
    CONSTRAINT ck_room_messages_sequence CHECK (room_seq BETWEEN 1 AND 9223372036854775806)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 历史写入和水位

1. 历史组按 Kafka offset 顺序裁决同一分区的一批记录。一个 MySQL 事务处理一批，按房间 UUID 的固定顺序创建或锁定涉及的状态行。不同分区可以并行处理。
2. 本批使用同一个 UTC 截止时间。已达到保留截止的事件直接完成历史处理。对其余事件，按 `(room_id, room_seq)` 和 `message_id` 批量读取已有记录，并维护本批已接受事件的两个索引。已持久化的记录优先。批内没有已持久化记录时，Kafka offset 更早的有效事件优先。
3. 两个键都未占用时接受新记录，即使它的序号低于或跳过当前最大值。两个键指向同一记录且不可变字段全部相同时，按幂等重放跳过。任一键指向不同的数据时，仅跳过本条冲突事件。同批其余记录继续处理。
4. 批量插入通过裁决的新记录。唯一键异常只有在重新读取后能指认具体冲突时才按上一步处理。发生并发键竞争时回滚当前事务，以新事务重读两个键并重新裁决。无法明确归因或重试仍失败时，按数据库失败保留原 Kafka 位置。使用普通插入保留异常语义。
5. 只用本批成功插入的最大序号推进各房间的 `latest_stored_seq`，取原值与本批值中较大者。历史记录与最大序号在同一事务提交。数据库已确认提交后，才向 Kafka 提交本批最后一条记录之后的 offset。写入失败或提交结果不明时从原位置重读。数据库已提交但 Kafka 位点未提交时，由同一规则完成幂等重放。 [历史消费确认](event-contract.md#三个输出消费组)

| 输入事件 | 历史处理 |
| --- | --- |
| `100` 已落库，随后收到 `103` | 写入 `103`，最大序号更新到 `103` |
| 未超过保留期的 `101` 迟到，两个键均未占用 | 写入 `101`，最大序号保持 `103` |
| 房间、序号、消息 ID、发送者账号与显示名、正文、受理时间全部一致 | 跳过重复事件，原记录和时间戳保持原值 |
| 同房间同序号但消息 ID 不同，或同消息 ID 对应的其他不可变字段变化 | 保留先落库或批内先接受的记录。记录本条冲突并继续处理 |

## 处理阻断记录

三实例新增共享的 `room_message_processing_blocks`。记录仅用于必须修复后才能恢复的处理错误。正常的分区交接、积压和临时依赖异常由运行状态与监控反映。Kafka 消费组与 Topic 名使用包含实际命名空间的完整名称。

| 字段 | 定义 |
| --- | --- |
| `consumer_group`、`topic_name`、`partition_no` | 唯一定位职责组及受影响 Kafka 分区。 |
| `room_id` | 分区级记录用空字符串。历史容错切换前留下的房间级记录使用规范房间 UUID。 |
| `reason` | `OFFSET_OUT_OF_RANGE` 表示已提交位置超出可读日志范围。`PROCESSING_ERROR` 表示已定位到分区且需要修复的处理错误。`HISTORY_SEQUENCE_CONFLICT` 是切换前可能残留的旧原因。 |
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
2. 历史序号缺口和单条数据冲突按历史写入规则完成处理。可判定的冲突使用结构化日志和计数。历史分区级持久故障影响映射到该分区的房间历史查询及进度通知。广播、回执和正式接收的阻断分别按各自职责范围生效。
3. 修复时按记录核对仍可重放的 Kafka 位点、历史数据和最大落库序号。清除时核对记录仍是本次已核验的版本。正常重启、分区转移和短期状态过期均保留阻断记录。旧历史冲突记录按发布步骤逐条核验并清除。 [分区交接](event-contract.md#三实例分区交接与接入判断) · [故障处置](deployment-and-operations.md#故障处置) · [版本切换](deployment-and-operations.md#历史容错版本切换)

## 历史查询和分页

历史状态、阻断记录和消息页从 MySQL 主库的同一一致性快照读取。没有状态行时使用 `latestStoredSeq=0`。同一请求只计算一次 UTC 截止时间，查询使用房间主键范围及时间过滤，返回公开字段。

目标分区存在历史持久阻断时返回不可用。切换期间仍尊重尚未处置的旧历史阻断记录。正常交接、落库延迟及序号缺口继续返回快照内的可读页。WebSocket 历史进度通知采用相同阻断范围。 [阻断记录](#处理阻断记录) · [历史接口](api.md#查询历史)

```sql
-- 新进房读取最近一段。查询倒序返回，服务层截取本页后转成升序。
SELECT room_seq, message_id, sender_name, content, sent_at
FROM room_messages
WHERE room_id = :roomId AND room_seq <= :latestStoredSeq
  AND sent_at > :cutoff
ORDER BY room_seq DESC
LIMIT :fetchSize;

-- 向上翻页，排除客户端已有的边界弹幕。
SELECT room_seq, message_id, sender_name, content, sent_at
FROM room_messages
WHERE room_id = :roomId AND room_seq < :beforeSeq
  AND room_seq <= :latestStoredSeq AND sent_at > :cutoff
ORDER BY room_seq DESC
LIMIT :fetchSize;

-- 范围分页。读上限取请求指定上限与本次最大落库序号中的较小者。
SELECT room_seq, message_id, sender_name, content, sent_at
FROM room_messages
WHERE room_id = :roomId AND room_seq > :afterSeq AND room_seq <= :readThrough
  AND sent_at > :cutoff
ORDER BY room_seq ASC
LIMIT :fetchSize;
```

三种查询的 `fetchSize` 均为 `limit + 1`，最多向服务层返回 101 行。服务层保留排序后的前 `limit` 行，额外一行只用于判断该方向还有记录。最近页和向上页截取后再转成升序，并用结果中最小的 `room_seq` 生成 `nextBeforeSeq`。范围页仍用结果中最大的 `room_seq` 推进 `nextAfterSeq`。

向上翻页沿 `(room_id, room_seq)` 主键对同一房间做倒序范围扫描，使用同一次请求的 UTC 截止时间过滤过期记录，扫描会越过缺号或已删除的序号。每页各自读取一致性快照。若迟到记录在某个范围已读完后才落库，重新查询覆盖该序号的范围可以读到它。此项设计复用现有表和索引，无需 Flyway 迁移或历史回填。 [历史接口字段](api.md#查询历史)

## 保留和迁移

历史从正式事件的 `sent_at` 起保留 24 小时。历史查询、入库重放和清理统一按 UTC 计算 `cutoff = 当前时间 − 24 小时`，到达边界即过期。已过期的 Kafka 重放不会重新入库。迟到事件在保留窗口内入库后，只剩从受理时点计算的保留时间。

清理任务按 `(sent_at, room_id, room_seq)` 的稳定顺序删除 `sent_at <= cutoff` 的记录，每批最多 500 行。清理可以跨序号缺口，保留仍在窗口内的较小序号。清理只删除消息行，即使最后一条消息过期，房间最大落库序号仍保留。历史唯一键可核对当前仍在库中的消息。被清理记录的正常重放由原 `sent_at` 的过期规则处理。

现有环境通过追加 Flyway 迁移撤下旧范围约束，将 `stored_through` 更名为 `latest_stored_seq` 并保留数值，删除 `earliest_seq`，再应用目标约束和 `sent_at` 清理索引。原消息行与唯一键保留。迁移后按照新的截止规则读取并逐批清理，耗时较长的消息可能比原来更早过期。由新旧版本切换流程控制迁移顺序。 [版本切换](deployment-and-operations.md#历史容错版本切换) · [InnoDB 持久化](https://dev.mysql.com/doc/refman/8.4/en/mysql-acid.html)
