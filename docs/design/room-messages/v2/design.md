# 直播间弹幕 V2 设计

弹幕账号冷却和发送熔断统一在单实例保护模块定义。本文的提交部分采用已确认的熔断和单次提交方案。 [业务规则与熔断范围](../../resilience/design.md) · [发布与切换](../../resilience/deployment-and-operations.md#发布与回退)

## 背景、目标与范围

V1 在 JVM 内完成弹幕受理、短期去重和本机广播。V2 由 Kafka 保存提交和正式弹幕，Redis Pub/Sub 转发实时事件，MySQL 提供历史查询。首轮在一个 LiveFlow 进程中运行完整链路，HTTP、Kafka 消费和 WebSocket 位于同一部署单元。按房间的消息路由允许后续增加应用实例。 [V1 设计](../design.md) · [部署与运行](deployment-and-operations.md)

本轮覆盖纯文本发送、异步回执、房间内有序广播、历史持久化和断线补拉。画面滚动弹幕、在线人数、超大房间实时采样和多应用实例部署留待后续讨论。

## 整体设计

```mermaid
flowchart LR
    Vue["Vue 发送"] --> MVC["Spring MVC"]
    MVC --> Breaker["Resilience4j 发送熔断"]
    Breaker --> Lookup["发送查询和 Redis 原子预约"]
    Lookup --> Input["Kafka 提交 Topic"]
    Input --> Streams["Kafka Streams 正式接收"]
    Streams --> Events["Kafka 正式弹幕 Topic"]
    Streams --> Outcomes["Kafka 回执 Topic"]
    Outcomes --> Receipts["Redis 回执查询"]
    Events --> Relay["广播消费组"]
    Relay --> Channel["Redis 房间频道"]
    Channel --> Socket["本机 WebSocket 连接"]
    Socket --> Vue
    Events --> History["历史消费组"]
    History --> Database["MySQL 历史与落库进度"]
    Database --> Vue
```

| 参与方 | 职责 |
| --- | --- |
| Vue 房间页 | 管理发送草稿和异步回执，合并实时和历史弹幕，按连续房间序号展示。 |
| `liveflow-api` | 提供 Spring MVC 接口和 WebSocket，使用可信 JWT 身份，按房间管理连接和入队顺序，回传历史进度。 |
| `liveflow-core` | 维护输入校验、发送者显示名、正式接收与去重规则、历史查询及写入事务。业务依赖通过接口承接。 |
| `liveflow-infrastructure` | 适配 Kafka Producer、Streams 处理、Kafka 消费与确认、Redis Lua、房间频道和回执投影。 |
| Kafka | 保存提交、正式事件和终态回执。同一房间的提交按同一分区处理。 |
| Redis 与 JVM | Redis 保存鉴权状态、提交预约、短期回执并向当前订阅者广播。JVM 保存实际连接、有界队列和房间订阅关系。 |
| MySQL | 保存历史事件和已连续落库的水位。用户资料、房间和鉴权数据沿用各自模块定义。 |

## 端到端流程

### 提交与正式接收

1. Vue 为一次发送固定正文和 `clientMessageId`，使用 Bearer 和 CSRF 规则发起一次 POST。后端规范化正文，先取得发送熔断许可，再读取已登记的提交、检查房间和账号、取得显示名。 [发送接口](api.md#发送弹幕) · [发送熔断](../../resilience/design.md#发送熔断)
2. Redis 原子绑定正文摘要，登记账号冷却并授予一次 Kafka 发布许可。同账号跨房间、标签页和会话共用两次新预约至少间隔 10 秒的规则。Broker 确认后返回 `202`，遇到确认不明时由页面提示未知并提供可用的回执查询。 [账号额度和提交绑定](storage.md#账号额度和提交绑定) · [生产和确认](event-contract.md#生产和确认)
3. Kafka Streams 按提交 Topic 的分区顺序裁决。同一会话、房间和客户端标识下，首个有效命令分配 `messageId`、`roomSeq` 和 `sentAt`，将正式事件、终态回执、去重状态和房间序号纳入同一 Kafka 事务。相同正文的后续命令取回原结果，异文生成冲突回执。 [正式接收和顺序](event-contract.md#正式接收和顺序)
4. Vue 通过回执接口确认正式接收。已提交的正文快照独立保存，用户可继续编辑下一条。消息列表通过 WebSocket 和历史查询更新。 [发送交互](api.md#发送交互) · [回执查询](api.md#查询提交回执)

### 广播与历史

1. 广播组按分区串行发布到 Redis 房间频道。实例在房间订阅就绪后将 `ready` 排入连接，再按房间事件顺序处理入队。连接独立写入，达到队列条数、字节数或等待时间上限时独立关闭。 [频道与订阅](storage.md#房间频道与订阅) · [连接资源](storage.md#jvm-连接资源)
2. 历史组独立读取正式事件，按分区批量提交 MySQL。历史记录与该房间的连续落库水位同时写入，写入成功后才提交该分区消费进度。一个数据库故障可能延迟历史读到最新事件，广播组可以独立推进。 [历史写入与进度](database.md#历史写入和水位)
3. 历史与实时复用 `messageId`、`roomSeq`、`senderName`、`content` 和 `sentAt`。同一房间按 `roomSeq` 排序，`sentAt` 用于本地秒级展示。昵称变更影响后续新提交，正式事件保留当时显示名。 [弹幕字段](api.md#弹幕数据)

### 进房、断线与补拉

1. 先申请一次性 Ticket，建立 WebSocket 并开始缓冲实时消息。首次进房读取最新历史窗口，以历史连续水位建立起点。重连保留当前房间和会话的最后连续序号。登录会话切换、换房和页面卸载时清理旧房间的回执查询、缓冲与重连任务。 [WebSocket 协议](api.md#websocket-接收协议)
2. 若连续游标为 40、实时缓冲中先收到 42，按 `afterSeq=40&throughSeq=41` 读取历史。历史暂未写到 41 时保留 40。得到 41 后按 41、42 展示，随后推进游标。合并时丢弃已经处理的序号；同一序号对应不同消息时触发重新同步并记录异常。
3. Redis 订阅恢复和历史水位前进时，网关发送控制帧驱动补拉，即使丢失的是房间最后一条弹幕。前端限制补拉并发、缓冲大小和轮询频率。超出历史保留窗口或客户端缓冲上限时，提示用户并重新读取最近窗口。列表仍保留最近 100 条，沿用底部跟随与向上阅读时保留位置的交互。 [历史接口](api.md#查询历史) · [恢复与缓冲](api.md#前端合并与恢复)

## 异常、并发与恢复

| 场景 | 系统行为 |
| --- | --- |
| 发送熔断或半开名额已满 | 发送入口提前返回 `503`。回执、历史和 WebSocket 接收各自按依赖状态处理。 |
| 发送超时，Kafka 可能已经写入 | 显示结果未知。有预约 ID 时只读查询。Kafka 和 Redis 按原预约处理随后到达的结果。 |
| Redis 发布成功后广播进程退出 | Kafka 可能再次投递同一正式事件。事件标识和序号保持一致，浏览器只显示一次。 |
| Redis 订阅中断或连接过慢 | 受影响连接重新同步。历史保存仍由独立的消费组推进。 |
| MySQL 写入后消费者退出 | Kafka 可能重放该批。历史唯一键和连续进度校验保证重复写入得到相同结果。 |
| 历史消费积压 | 实时消息仍可到达。出现缺口时客户端在连续水位后补拉；水位追平后再继续。 |
| 同一房间成为热点 | 网关按房间调度，按连接隔离慢消费者。入口有界控制在途请求，按容量拒绝新的提交。 |

## 专业文档

| 内容 | 定义 |
| --- | --- |
| HTTP、回执、WebSocket、历史补拉和前端合并 | [接口设计](api.md) |
| MySQL 表、索引、事务、历史分页与保留 | [数据库设计](database.md) |
| Kafka Topic、事件结构、事务、消费与顺序 | [消息队列设计](event-contract.md) |
| Redis Key、TTL、Lua、Pub/Sub 与连接队列 | [Redis 与本机状态设计](storage.md) |
| 本地拓扑、参数、容量、发布和故障处置 | [部署与运行设计](deployment-and-operations.md) |
