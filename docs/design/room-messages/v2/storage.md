# 直播间弹幕 V2 Redis 与本机状态设计

## 数据归属

账号当前会话和一次性 Ticket 延续现有 Redis 实现。V2 新增账号发送额度、提交绑定、回执投影和房间频道。终态回执可由 Kafka 重建，正式弹幕和顺序由 Kafka Streams 与 Kafka Topic 保存。JVM 持有 WebSocket、房间订阅状态和发送队列。 [现有 Ticket 与会话复查](../storage.md#redis-ticket) · [认证会话](../../auth/session.md) · [Kafka 回执](event-contract.md#终态回执事件)

## 账号额度和提交绑定

`<env>` 为部署环境名。下表中的 `{<userId>}` 代表围住实际账号 ID 的 Redis Cluster 哈希标签。一个 Lua 调用访问的账号额度和提交绑定同槽。额度以可信账号 ID 为粒度，跨标签页和房间共享。 [Redis Cluster 哈希标签](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/#hash-tags)

| Key | 类型和值 | 生命周期 |
| --- | --- | --- |
| `<env>:liveflow:rooms:message:{<userId>}:rate` | ZSET，成员为 `submissionId`，分数为首次入队许可的 UTC 毫秒 | 滚动 10 秒窗口，从最近一次新增计数起保留 10 秒 |
| `<env>:liveflow:rooms:message:{<userId>}:submit:<sessionId>:<roomId>:<clientMessageId>` | Hash，保存 `submissionId`、`contentHash`、`senderName`、`admittedAtMs`、`phase`、`leaseId`、`leaseUntilMs` | 从首次入队许可起保留 30 分钟，所有重试保持原绝对截止 |
| `<env>:liveflow:rooms:message:{<userId>}:receipt:<sessionId>:<roomId>:<submissionId>` | String，存放终态回执 JSON | 按 Kafka 回执中的绝对 `expiresAt` 过期 |

发送入口先校验当前会话、房间和正文。对已经确认入队的相同提交，返回原入队回执。新提交读取发送者显示名后执行 Lua。Lua 再检查已有绑定，防止读取资料期间另一请求已经登记。绑定保存第一个获得许可的显示名快照，后续生产命令使用该值。

入队脚本接收额度键与绑定键，参数为服务端预生成的候选 `submissionId` 和 `leaseId`、规范化正文摘要及显示名。额度窗口、允许次数、预约保留期和租约时长由应用配置传入。首次创建采用候选标识，重复请求以既有绑定为准。租约确认脚本只访问绑定键，参数为本次 `submissionId` 和 `leaseId`。两个脚本都以 Redis 的毫秒时间判断有效期。

1. **绑定不存在时** · 使用 Redis 当前时间移除窗口之外的成员。窗口内已达 5 条则返回 `RATE_LIMITED` 和距离最早记录出窗的毫秒数。否则原子写入绑定、登记一次额度并授予发送租约。前端用相同标识重试时沿用原额度。
2. **绑定已存在时** · 先比较正文摘要。不同摘要返回 `CONFLICT`。相同摘要且为 `QUEUED` 时返回原标识和期限。仍待确认且租约有效时返回 `IN_FLIGHT`，调用方稍后用原标识重试。待确认且租约过期时，只在首次许可后 10 分钟内授予新租约，保留原标识、显示名和时间。重试窗口结束后，客户端仍可以在查询期内查询回执。
3. **生产返回时** · 调用方持有本次 `leaseId`。收到 Kafka 确认后，按绑定标识和租约标识将该绑定置为 `QUEUED`。Redis 写回异常时仍可使用本次已收到的 Kafka 确认返回 `202`。生产确认不明时保留待确认状态，租约到期后允许重新生产。已过期租约产生的迟到写回不能覆盖新租约。

初始发送租约为 5 秒，生产者入队及确认等待预算合计 3 秒。应用收到 `IN_FLIGHT` 时使用 `MESSAGE_SUBMISSION_UNKNOWN`，可附整数秒 `Retry-After`。额度在首次获得许可时计入，后续故障时自然出窗；按请求速率和进程级在途上限约束额外传输流量。若 Redis 预约在故障中丢失，重试可拿到新的 `submissionId`。已进入 Kafka 的同一业务提交仍按正式去重状态裁决，相同正文复用原正式消息。生产应为会话、额度和回执预留 Redis 内存，使用拒绝新写入的淘汰策略保护有期限的绑定。 [HTTP 状态映射](api.md#错误和重试) · [正式去重](event-contract.md#正式接收和顺序)

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
