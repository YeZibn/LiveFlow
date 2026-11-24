# 直播间弹幕 V2 Redis 与本机状态设计

## 数据归属

Redis 保存账号当前会话、一次性 Ticket、发送冷却、提交绑定和回执投影，并提供房间频道。终态回执可由 Kafka 重建，正式弹幕和顺序由 Kafka Streams 与 Kafka Topic 保存。JVM 持有发送断路器、WebSocket、房间订阅状态和发送队列。 [发送熔断](../../resilience/design.md#发送熔断) · [现有 Ticket 与会话复查](../storage.md#redis-ticket) · [认证会话](../../auth/session.md) · [Kafka 回执](event-contract.md#终态回执事件)

## 账号额度和提交绑定

`<env>` 为部署环境名。下表中的 `{<userId>}` 代表围住实际账号 ID 的 Redis Cluster 哈希标签。一个 Lua 调用访问的账号额度和提交绑定同槽。冷却以可信账号 ID 为粒度，跨标签页、房间和登录会话共享。 [Redis Cluster 哈希标签](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/#hash-tags)

| Key | 类型和值 | 生命周期 |
| --- | --- | --- |
| `<env>:liveflow:rooms:message:{<userId>}:cooldown` | String，开启冷却的预约 `submissionId` | 默认在首次入队许可后 10 秒到期 |
| `<env>:liveflow:rooms:message:{<userId>}:submit:<sessionId>:<roomId>:<clientMessageId>` | Hash，保存 `submissionId`、`contentHash`、`senderName`、`admittedAtMs`、`phase`、`leaseId` | 从首次入队许可起保留 30 分钟，后续读取和状态写回沿用原绝对截止 |
| `<env>:liveflow:rooms:message:{<userId>}:receipt:<sessionId>:<roomId>:<submissionId>` | String，存放终态回执 JSON | 按 Kafka 回执中的绝对 `expiresAt` 过期 |

通过发送许可后先读已确认入队的绑定，再查房间与发送者。新提交读取显示名后执行 Lua。Lua 再检查已有绑定，防止读取资料期间另一请求已登记。首次绑定持有正文摘要及显示名快照，生产命令使用该值。

入队脚本接收冷却键、绑定键、候选 `submissionId` 和 `leaseId`、规范化正文摘要、显示名及相关时长。`leaseId` 是首次创建时生成的单次发布许可标识，后续用于核对写回归属。`phase` 初始为 `PENDING`，Broker 确认后可更新为 `QUEUED`。脚本使用 Redis 毫秒时间裁决截止。 [限流存储设计](../../resilience/storage.md)

1. **已有绑定**。异文返回冲突。同正文且为 `QUEUED` 时返回原预约及期限；同正文且为 `PENDING` 时返回待确认及原预约信息。这两种同正文情形均为只读，直接或并发的重复请求都拿不到新的发布许可。
2. **新绑定**。冷却生效时返回剩余毫秒并保留原截止。否则，同一次脚本原子建立绑定与冷却，只有首次创建者获得发布许可。两者基于同一次 Redis 当前时间。冷却键和预约键越过各自的截止时间后自然过期。
3. **生产结果**。仅在 Broker 确认后，按 `submissionId` 和 `leaseId` 核对原绑定，再置为 `QUEUED`。过期或已换身份的绑定跳过写回。写回故障时，本次 `202` 仍以 Broker 确认为准。结果未知的绑定保留待确认，终态由回执查询提供。

冷却从首次获得许可时开始。生产失败、未进入 Kafka 或无法确认时仍按原截止到期。旧绑定自然到期，脚本忽略旧 `leaseUntilMs` 并删除续领分支。单次许可由有效的 Redis 绑定保障；Redis 状态丢失时，已进入 Kafka 的相同提交还受 Streams 去重状态及其保留期保护。生产为会话、额度和回执预留 Redis 内存，使用拒绝新写入的淘汰策略保护有期限的绑定。 [HTTP 状态映射](api.md#http-错误) · [正式去重](event-contract.md#正式接收和顺序)

## 回执投影

回执消费者读取已提交的 Kafka 终态，通过作用域 Key 写入 Redis，保存 `accepted` 或 `rejected` 的原始业务结果。重复事件使用同一终态及绝对截止。不同终态冲突记录为异常。查询只用已验证的账号、会话和房间组装 Key。客户端提供的 `submissionId` 只负责定位该作用域内的结果。

查无投影时接口返回 `unconfirmed`。它覆盖暂未完成、投影延迟、已过期或持有其他作用域标识等情况。Redis 不可用时返回服务暂不可用。重新投影时从 Kafka 回执 Topic 扫描保留记录，仅恢复尚未到期的终态。定期回收 Kafka Streams 的业务去重状态与预约终态。 [回执接口](api.md#查询提交回执) · [Kafka 消费确认](event-contract.md#三个输出消费组)

## 房间频道与订阅

频道格式为 `<env>:liveflow:rooms:<roomId>:messages:v1`。广播组将正式弹幕转换为 WebSocket `message` 形状后发布，保留 `messageId` 和 `roomSeq`。账号 ID、会话标识和发送回执留在各自的可信访问范围内。环境前缀用于隔离共享 Redis 上的频道。

1. 房间首条本机连接通过 Ticket 鉴权后注册订阅。收到 Redis 订阅确认后，同一房间调度器先为连接排入 `ready`，随后才允许该连接使用实时入队。短暂交接期间错过的消息由历史补拉覆盖。
2. 同房间的连接复用订阅。最后一条连接离开后给予 5 秒取消订阅宽限。引用计数和订阅代次受房间串行状态保护；迟到的订阅确认或取消回调只作用于发起时的代次。
3. Redis 订阅中断时标记相关房间不可实时接收。恢复后重订阅活跃房间，在恢复实时入队时发送 `resync_required`。网关按批读取活跃房间的 MySQL 水位，水位提高时发送 `history_progress`，覆盖恢复之后才落库以及没有后续弹幕的情况。
4. 同一房间的发布和本机入队均保持串行。广播重放与跨故障的迟到消息由 `roomSeq` 和 `messageId` 在接收端合并。Redis Pub/Sub 期间漏收的事件由 MySQL 补拉。 [Redis Pub/Sub 投递](https://redis.io/docs/latest/develop/pubsub/#delivery-semantics) · [前端恢复](api.md#前端合并与恢复)

## JVM 连接资源

保留每连接串行写线程。房间成员、首次订阅及广播入队由同一房间调度顺序保护；连接上限与待认证配额独立计数，房间锁内只处理内存状态和有界入队。每条弹幕在实例内序列化一次，多个连接队列复用同一不可变内容。

每连接初始上限为 **64 条、128 KiB UTF-8 待发送载荷、最老待发项等待 2 秒**。触及任一上限时将该连接移出房间，由写线程关闭。容器单次写入超时仍为 5 秒，防止正在写入的慢连接长期占用资源。登录会话继续周期复查，复查请求错峰，未知或失效的会话关闭连接。 [运行参数](deployment-and-operations.md#初始参数)
