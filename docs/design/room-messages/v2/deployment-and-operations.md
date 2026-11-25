# 直播间弹幕 V2 部署与运行设计

## 部署拓扑

现行本地流程运行一个 Spring Boot 进程，同进程启用 MVC、WebSocket、Kafka Producer、Kafka Streams、三个输出消费组和 Redis 订阅调度。三实例方案沿用同一 JAR 与这些职责。Topic 显式创建，Streams 维护内部状态 Topic。单 Broker 环境使用一副本和最小同步副本数 1。 [本地 Compose](../../../../compose.yaml) · [Topic 约定](event-contract.md#topic-和消费组)

| 环境 | Kafka 与状态 | 数据与接入 |
| --- | --- | --- |
| 本地与单实例压测 | 1 个 Broker，业务和内部 Topic 1 副本；Streams 初始使用 1 个线程 | 本机 Compose；Vite 转发 `/api` 与 `/ws`，使用已配置的浏览器来源 |
| 三实例联调 | 3 个应用 JVM 共享既有 Topic、消费组和 6 个分区，各自持有 Streams 状态目录 | 同源代理连接三个后端，统一 Redis、MySQL 和 JWT 签名配置 |
| 生产部署基线 | 3 副本、至少 2 个同步副本；业务 Producer `acks=all`，Streams 状态目录使用持久卷或按 changelog 重建 | 同源 TLS 代理，保留升级与空闲连接超时设置；MySQL 与 Redis 采用独立资源配额 |

生产 Redis 如共用认证与弹幕业务，应分别观测命令延迟和连接预算。Pub/Sub 使用独立连接，避免订阅生命周期占用普通命令连接。生产 Kafka 的认证和 Redis 环境前缀由部署配置提供。 [Kafka Topic 参数](https://kafka.apache.org/43/configuration/topic-configs/) · [Redis Pub/Sub 环境隔离](https://redis.io/docs/latest/develop/pubsub/#database--scoping) · [现有本地启动说明](../../../../README.md#本地启动)

房间存在性缓存采用独立 Redis。缓存配置、回源预算、分阶段启用和故障隔离联调由房间模块定义。 [房间缓存部署与运行](../../room/deployment-and-operations.md)

## 三实例部署

同一个环境部署 A、B、C 三个对等进程。三台都接收 HTTP 与 WebSocket 请求，并加入同样的后台任务组。同机联调使用独立端口和状态目录。需要承受主机失效时，将三个进程分布到不同主机，并单独配置入口和共享依赖的可用性。单 Broker、单 Redis 和单 MySQL 的联调环境用于验证应用进程退出与接管。

| 配置项 | 三实例约定 |
| --- | --- |
| `LIVEFLOW_INSTANCE_ID`、`LIVEFLOW_CLUSTER_MEMBER_IDS` | 分别表示本机唯一标识与同一环境的成员标识。联调可用 `a`、`b`、`c`，三台使用同一份成员清单。单实例缺省成员为 `local`。 |
| `LIVEFLOW_PORT`、`LIVEFLOW_ROOM_MESSAGES_STATE_DIRECTORY` | 同机三个进程须用不同的端口和目录。示例端口 `18081`、`18082`、`18083`。 |
| MySQL、Redis、Kafka、JWT | 三台指向同一套服务并使用相同的 JWT 签名密钥、Redis 环境名、Kafka 命名空间、Streams 应用 ID 和 Group ID。 |
| `LIVEFLOW_ROOM_MESSAGES_ALLOWED_ORIGIN` | 代理对外域名和协议对应的浏览器 Origin。生产使用 HTTPS 与安全 Cookie。 |
| `LIVEFLOW_FORWARD_HEADERS_STRATEGY`、`LIVEFLOW_TRUSTED_PROXY_REGEX` | 启用受信转发头时，前者设为 `native`，后者匹配能直连后端的代理来源。缺省只信任回环地址，后端应仅在受控网络接受代理流量。 |
| `LIVEFLOW_ROOM_MESSAGES_SNAPSHOT_FRESHNESS`、`LIVEFLOW_ROOM_MESSAGES_HANDOFF_GRACE`、`LIVEFLOW_ROOM_MESSAGES_BACKLOG_LIMIT` | 联调起点依次为 12 秒、8 秒和 2 分钟。交接窗口不超过快照有效期，积压阈值为正式接收的 12 分钟期限留出恢复余量。 |
| `LIVEFLOW_ROOM_MESSAGES_GROUP_SESSION_MILLIS`、`LIVEFLOW_ROOM_MESSAGES_GROUP_HEARTBEAT_MILLIS` | 分别为 10,000 和 2,000 毫秒。用于 Kafka Streams 及输出组的经典协议，Broker 需接受该会话时限。正常退出主动离组，异常退出由 Kafka 超时检测。 |
| 入口限制 | HTTP 和 WebSocket 新握手在可接流量的实例间分配。WebSocket 的代理空闲超时覆盖心跳，发送 POST 禁用代理自动重试。入口覆盖来自外部的转发头，只从受信代理解析客户端地址。 |

入口就绪指本机已经启动、能够接收请求且未进入排空。代理或发布流程直接读取各上游的 `/actuator/health/readiness`，排空时从新流量摘除。共享依赖和分区的可用性由各接口处理。`/actuator/health` 用于观察本机和依赖，发布流程另检查分区状态、消费组位点与房间水位。普通 Nginx 模板使用被动故障摘除，计划排空由运维先标记上游 `down` 并重载代理。 [接入判定](event-contract.md#三实例分区交接与接入判断) · [WebSocket 代理](https://nginx.org/en/docs/http/websocket.html)

首次联调按以下顺序启动。

1. 启动共享依赖，预先执行本轮 Flyway 迁移并核对 Topic 配置。迁移只由发布流程中的一个执行者运行，实例启动用于验证共享表和 Topic 已就绪。
2. 以独立端口、实例标识和状态目录启动 A、B、C。等待三个进程通过入口就绪检查，并核对提交分区的恢复状态和三个输出组的分区分配。
3. 将三个上游加入同源代理。用不同实例完成 Ticket 签发和兑换、发送和回执查询。三台各接一个观众，核对同房间事件的 `messageId`、`roomSeq` 与 MySQL 读回结果一致。

从仓库根目录在三个终端分别启动同一 JAR。以下为实例 A 的联调配置，B、C 对应改用各自的实例标识、端口和目录。三个进程从环境取得同一签名密钥与依赖连接参数。

```bash
LIVEFLOW_INSTANCE_ID=a \
LIVEFLOW_CLUSTER_MEMBER_IDS=a,b,c \
LIVEFLOW_PORT=18081 \
LIVEFLOW_ROOM_MESSAGES_STATE_DIRECTORY="$PWD/backend/target/liveflow-streams-a" \
LIVEFLOW_ROOM_MESSAGES_ALLOWED_ORIGIN=http://127.0.0.1:18080 \
LIVEFLOW_LOCAL_SECURE_COOKIE=false \
LIVEFLOW_FORWARD_HEADERS_STRATEGY=native \
java -jar backend/liveflow-api/target/liveflow-api-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local
```

使用 [Nginx 联调模板](../../../../deploy/nginx/liveflow-three.conf)，将 Vue 构建产物放到配置的静态目录。代理监听 `127.0.0.1:18080`，同源托管页面，并将 `/api` 与 `/ws` 转发至三个实例。关闭 Secure Cookie 的配置仅用于回环 HTTP 联调。生产部署使用实际 HTTPS 域名和安全 Cookie。

## 初始参数

下表是首轮运行起点。面向生产的容量与保留值根据实际流量和恢复时间调整。历史保留、回执窗口和 Kafka 重放能力变更时同步更新相关契约。

| 范围 | 初始值 | 调整依据 |
| --- | --- | --- |
| Kafka 分区 | 提交、正式事件、回执 Topic 各 6 分区 | 不同房间可并行。提交与正式事件的分区变更依照迁移规则执行 |
| Kafka 保留 | 应用 Topic 72 小时；业务和回执状态窗口见事件文档 | 最长停机、原始提交积压、历史追平和磁盘容量 |
| Producer 预算 | 应用生产阶段预算约 3 秒，`max.block.ms=500`、`delivery.timeout.ms=2500`、`request.timeout.ms=1500` | 生产超时标记结果未知，保留已登记的预约和回执查询期限 |
| 广播新鲜窗口 | 正式受理后 10 秒 | 超期只由历史恢复，避免恢复后向实时连接突发回放 |
| 入口在途生产上限 | 每实例初始 256，满时拒绝新生产请求 | Kafka 延迟、内存和 MVC 并发预算 |
| 历史批写 | 每分区一次最多 200 条，低流量刷新间隔不超过 100 毫秒 | MySQL 提交开销和水位延迟 |
| 历史保留与清理 | 正式受理后 24 小时，按受理时间及主键顺序每批最多 500 条 | 持续平均消息量、磁盘余量与历史保障周期 |
| WebSocket 资源 | 先沿用每实例 512 条连接和 64 条待鉴权连接；每连接队列见 Redis 与本机状态设计 | 单机内存、出站带宽、握手峰值与复查成本 |
| 历史水位通知 | 每 3 秒批读活跃房间，单次查询最多 100 个 UUID，每实例按房间分摊读取 | DB 读取量与静默漏收恢复延迟 |

现有 [应用配置](../../../../backend/liveflow-api/src/main/resources/application.yaml) 提供 Kafka 地址。弹幕基础运行参数集中于 `liveflow.room-messages.v2`，启动时检查正数、时限关系、Topic 分区配置与启用的依赖。单次发布许可启用时撤下旧 `liveflow.room-messages.v2.lease-millis`。 [限流与熔断参数](../../resilience/deployment-and-operations.md) · [Redis 设计](storage.md) · [事件设计](event-contract.md) · [Kafka 生产等待参数](https://kafka.apache.org/43/configuration/producer-configs/)

## 本地启动和检查

1. 从仓库根目录运行 `docker compose up -d --wait`，再按本地参数创建业务 Topic。以下命令用于本地单 Broker。生产使用对应集群的创建工具和副本参数。

   ```bash
   for topic in liveflow.room-message.submissions.v1 liveflow.room-message.accepted.v1; do
     docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
       --bootstrap-server kafka:29092 --create --if-not-exists --topic "$topic" \
       --partitions 6 --replication-factor 1 --config min.insync.replicas=1 \
       --config retention.ms=259200000 --config cleanup.policy=delete
   done

   docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
     --bootstrap-server kafka:29092 --create --if-not-exists --topic liveflow.room-message.receipts.v1 \
     --partitions 6 --replication-factor 1 --config min.insync.replicas=1 \
     --config retention.ms=259200000 --config cleanup.policy=compact,delete
   ```

2. 按现行单实例流程启动后端。Flyway 应用 V9，应用创建缺少的业务 Topic 并检查已有配置。当前实现由同一个进程取得全部分区后开放相关接口。按 [现有本地启动说明](../../../../README.md#本地启动) 启动 Vue。
3. 查看 `/actuator/health` 和前端开发检查。排查历史积压时使用以下命令查看每分区进度，并结合 MySQL 房间最大落库序号与受理到落库延迟判断。

   ```bash
   docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server kafka:29092 --describe --group liveflow-room-message-history-v1
   ```

| 环境变量 | 用法 |
| --- | --- |
| `LIVEFLOW_ROOM_MESSAGES_STATE_DIRECTORY` | Streams 状态目录路径。本地默认使用临时目录，部署使用可持久化的挂载路径 |
| `LIVEFLOW_ROOM_MESSAGES_KAFKA_REPLICAS`、`LIVEFLOW_ROOM_MESSAGES_KAFKA_MIN_ISR` | 本地默认 1 和 1，生产三 Broker 使用 3 和 2。运维同时配置 Broker 内部事务日志及消费位点的可靠性 |
| `LIVEFLOW_ROOM_MESSAGES_CONNECTION_LIMIT` | 默认每实例 512，待鉴权连接上限为每实例 64。三实例容量按压测和故障余量调整。 |
| `LIVEFLOW_ROOM_MESSAGES_ACCEPTING` | 默认 `true`。`false` 暂停向 Kafka 写入新提交，后台消费者继续排空，已经确认的预约仍可返回 |
| `LIVEFLOW_ROOM_MESSAGES_V2_ENVIRONMENT`、`LIVEFLOW_ROOM_MESSAGES_V2_KAFKA_NAMESPACE` | 分别隔离 Redis 和 Kafka 测试环境。已有环境沿用原命名与消费组 |

## 发布与回退

### Kafka 运行时拆分切换

本轮采用整套新版切换与检查。三个应用实例统一使用同一新版构建，沿用当前 Kafka、Redis、MySQL 和分区分配所需的实例配置。 [组件设计](design.md#kafka-运行时拆分)

1. 切换时摘除原有应用入口，等待原进程完成在途 HTTP 请求及 WebSocket 排空后退出。记录现有 Topic、消费组位点和实例状态目录，待原进程全部退出后启动三个新版实例。
2. 新版在启动阶段统一创建或校验业务 Topic，初始化 Producer 和 Streams，启动正式输出消费组，再启动 Streams 和维护调度。各组件启动成功后进入运行态，新发送依然由目标分区准入规则裁决。部分启动失败时清理本次已经打开的资源，保留启动错误供排查。
3. 新版停机由外层先排空 HTTP 和 WebSocket。随后收回新发送准入并发布实例退出状态，停止调度新维护任务，在途维护在批次边界检查运行状态和租约。由各组件关闭其 Kafka 客户端。单个关闭失败后继续清理其余资源。
4. 核对三个新版实例均可接入、Streams 已恢复分区、各输出组接续原位点，检查跨实例发送、实时分发、回执和历史后开放流量。如需回退，先摘流并退出整套新版，再启动原应用构建。沿用现有表、Kafka Topic、状态目录与位点。

| 新版检查场景 | 预期行为 |
| --- | --- |
| 三台均运行新版 | 经不同实例签发 Ticket、建立连接和发送，观众收到同一正式弹幕。回执和历史均可在任意健康实例查询。 |
| 停止一台新版实例并重新启动 | 剩余实例继续接入，后台组从已提交位置接管。受影响的观众重连后通过最近历史窗口补读，同一消息只展示一次。 |
| 写入结果未知或数据库事务失败 | 发送名额随生产结果完成而归还。历史组维持原 Kafka 位点，恢复后可重放并保持已有记录。 |
| 回执重建与历史清理 | 每项任务按各自租约执行。回执重建恢复有效回执，正式消费位点由正式消费组推进。 |
| 组件启动中途失败 | 已创建的客户端被关闭，失败进程保持退出状态。问题修复后可用原配置重新启动。 |

### 历史容错版本切换

历史表字段和 WebSocket 进度字段均有变化，使用一次协调切换。保留三实例拓扑、各 Topic 与分区数、Streams 应用 ID 和 changelog，以及各消费组 offset。

1. 暂停弹幕发送入口，摘除旧版弹幕 HTTP 和 WebSocket 流量。记录历史组各分区已提交 offset、日志起点、现有阻断记录和数据库快照位置。允许旧历史消费者提交正在处理的批次，再确认三台旧进程的历史消费者和清理任务全部退出，以退出后的已提交 offset 为恢复基线。
2. 由单个迁移执行者运行追加的 Flyway 迁移，核对新字段、索引、历史行和进度值。在迁移完成且旧消费者全部退出后，启动新版并从原已提交位置接管。 [目标表结构](database.md#ddl)
3. 对残留的 `HISTORY_SEQUENCE_CONFLICT`，逐条核对实际历史消费组、Topic、分区、房间、旧记录版本、当前保留边界和已持久化的记录。确认原已提交位置可重放后，在新版接管后按完整主键和已核验的 `revision` 清除对应旧记录，从原位置重放。若原位置已越出 Kafka 保留范围，按位点越界单独处理。保留其他真实分区故障记录。 [阻断结构](database.md#处理阻断记录) · [故障处置](#故障处置)
4. 检查同分区内两个房间从原位点接续、最近历史响应和 WebSocket 进度通知。成对切换 Vue 与 API，关闭旧连接并让旧页面重新加载，再开放弹幕入口。 [接收协议](api.md#websocket-接收协议)
5. 迁移前可恢复原版流量。迁移后优先使用支持新表结构的修复版本。已有稀疏历史时，修复版本必须支持缺号和迟到写入，直接启用旧消费者会再次阻断分区。

### V2 单实例切换到三实例

以下记录的是引入三实例时的切换步骤。历史容错版本使用上文的协调切换。

1. 应用新增的共享阻断表迁移，保留服务使用的 Topic、Streams 应用 ID、changelog 和各消费组 offset。先以单实例运行新版，核对发送、回执、历史、重新连接与冷却规则。
2. 旧版只适合独占分区。确认旧版已退出共享应用和消费组后，再让 B、C 加入 A。确认全部提交分区有可用处理状态，投影位点和 MySQL 水位继续推进，再扩代理流量。
3. 日常滚动发布一次处理一台。先从代理摘除该实例，验证配置并重载，然后向已核验的应用进程发送 `SIGTERM`。Spring 退出就绪状态并进行 HTTP 优雅停机，后端随后关闭本机 WebSocket，再退出 Streams 与各消费组。等待旧进程结束后启动同标识的新进程。通过就绪检查，核对分区新分工和消费进度后回流，再发布下一台。
4. 回退到原单实例版本时，先停止给准备退出的两台分配新流量，等待已确认请求得到终态并检查落库水位。让两台退出共享 Kafka 应用和消费组，确认只留单个处理者，再切回单实例版本。保留新增表、历史、Topic、状态及位点；仍存在持久阻断时先核对受影响分区的处置结果。

### V1 与 V2 的历史切换

发送熔断和单次提交的历史发布步骤由保护模块维护。 [发送方案发布与回退](../../resilience/deployment-and-operations.md#发布与回退)

1. 先应用历史表的新增 Flyway 迁移，并创建三个 Kafka Topic。启动时核对分区、保留和副本配置。回执投影组启动后才开放回执路由。
2. 从 V1 切换时先在入口暂停 `POST /api/v1/rooms/{roomId}/messages` 并关闭旧连接，随后成对发布 Vue 和后端。待六个提交分区恢复、后台消费组确认分区后恢复入口流量，旧页面重新加载后申请新 Ticket。同一入口在切换时只开放一套弹幕协议。
3. 回退时在流量入口暂停新的 V2 提交并保留当前进程，或者以 `LIVEFLOW_ROOM_MESSAGES_ACCEPTING=false` 启动 V2 进程排空。用消费组位点核对提交、正式事件和回执均已处理至停止写入后的终点，确认 MySQL 水位和回执，再正常关闭 V2 并启用 V1 前后端。保留历史表、Topic、Streams 应用 ID、状态存储和消费进度。
4. 再次启用 V2 前先暂停 V1 并关闭旧连接，再用原命名和状态配置启动 V2。以同一房间的 V2 历史水位验证新的正式序号继续递增。回退期间由 V1 处理的弹幕随 V1 进程生命周期管理。

## 故障处置

| 故障 | 行为与恢复 |
| --- | --- |
| Kafka 无法确认写入 | 返回结果未确认，有预约标识时通过 GET 查询。发送依赖故障计入熔断，恢复由用户发起的新弹幕验证。 [发送熔断](../../resilience/design.md#发送熔断) |
| 正式受理积压 | 监控最老待处理提交的年龄，积压接近可接收期限时收紧入口。逾期命令按事件契约生成终态或结束查询期，减少长时间不确定。 |
| 单实例异常退出 | 入口移出该实例。健康实例继续接收 HTTP 和 WebSocket，Kafka 重新分配原任务。受影响观众重新申请 Ticket 并刷新最近历史。 |
| 单实例 Streams 客户端异常 | 关闭本机 Streams 客户端并告警。健康实例接管；确认持续故障时记录受影响分区并保留位点。 |
| Redis 发布暂时失败 | 广播组暂停受影响分区并退避，继续维持消费者心跳。恢复后按顺序重放。超过 10 秒新鲜窗口的事件推进广播组进度，历史组持续处理。网关可通过历史进度通知触发最近窗口刷新。 |
| Redis 整体不可用 | 认证、Ticket、提交限流、回执投影和广播可能同时受影响。已正式提交的 Kafka 事件保留，历史消费可单独继续。服务恢复后按 Kafka 重建未过期回执。 |
| MySQL 无法写入或提交结果不明 | 历史组保留消费位置并按依赖故障恢复，浏览器继续展示已收到的实时弹幕。需要读取房间和资料的新提交可能因 MySQL 失败被拒绝。 |
| 历史偶发缺号或单条事件发生键冲突 | 有效事件继续落库。保留期内的迟到事件可补写。冲突只跳过本条并记录，后续同房间与同分区其他房间继续推进。 |
| 积压接近 Kafka 保留期 | 用分区已提交位置、最早可重放位置和积压年龄判断风险，优先保护日志保留并恢复正常消费。 |
| 已提交位点越界、事件无法解码或从旧库快照恢复 | 明确受影响分区，核对可恢复的 MySQL 数据和 Kafka 日志。持久分区故障按原位置阻断，恢复后再开放对应历史范围。恢复旧库时用备份和 Kafka 位点核对需要重放的范围。 |
| 应用重启或网关失去 Redis 订阅 | 重新申请 Ticket 或恢复活跃房间订阅，浏览器刷新最近历史窗口。回执可以从原 Topic 恢复。如需主动触发完整回执窗口重建，移除当前环境的 `<env>:liveflow:rooms:message:receipt-projection:<Kafka 命名空间或 main>` 标记。 |

持久阻断修复先核对职责组、Topic、分区、房间、故障位置和 `revision`。对位点越界先恢复数据或确认可验证的新位置。调整 Kafka 消费位点时先退出相关消费组的所有成员，按 Kafka 的消费组操作流程应用修复后的位点。确认 MySQL 最大落库序号和可重放记录后，按完整主键和已核验的 `revision` 清除该条阻断，重新接入并确认分区进度推进。 [阻断记录](database.md#处理阻断记录)

## 容量与观测

分别观察 HTTP → Kafka 确认、Kafka 确认 → 正式接收、正式接收 → 广播、正式接收 → MySQL 提交。按 P50、P95、P99 记录延迟；同时记录按分区的积压数量和年龄、回执未确认率、Redis 发布失败、慢连接关闭、出站字节和补拉次数。指标标签使用环境、阶段、消费组和分区等有界维度。房间和提交标识用于故障日志中的定位，上下文不含登录凭证或原始正文。

历史单条冲突按冲突类型计数，使用有界指标标签。结构化日志记录实际冲突事件的 Topic、分区、Kafka offset、房间、序号、来件消息 ID 和每条命中记录的已有消息 ID。重放可能再次产生诊断记录。单条冲突直接按写入规则收敛，持续异常可结合历史积压和落库延迟告警。 [单条写入处理](database.md#历史写入和水位)

单实例压测从 512 条连接逐步升至 1,000 条。普通房间以总发送 100 条/秒为起点，按默认 10 秒冷却至少分散到约 1,000 个有效发送账号，配置额外账号余量。热点场景以一间房 1,000 条连接、20 条弹幕/秒为起点，至少约 200 个有效发送账号。弹幕 V2 已归档的压测按当时每账号 10 秒 5 条的规则执行。记录请求数、Kafka 已确认提交数、正式接收数与 WebSocket 交付次数。历史容量使用持续平均流量估算，每秒持续 100 条约为每天 864 万条，生产保留值由可用磁盘、业务需求和滞后恢复预算共同确定。

三实例测试分别覆盖单房间、多房间、三台全量运行、掉一台后只剩两台，以及滚动发布。发压分开记录 Kafka 已确认、正式接收、Redis 广播、客户端唯一交付及 MySQL 入库。容量以剩余两台在目标负载下仍可维持的连接、发送延迟、交付缺失与积压收敛能力为准。记录重新分配期间最长停止推进时长、掉线人数、重新就绪耗时和补拉收敛时间。故障期间已获 `202` 的提交应按正常回执规则最终可查询。历史重放按房间序号返回当前可读记录，每个序号最多一条。

| 演练场景 | 观察到的目标行为 |
| --- | --- |
| A 发 Ticket、B 兑票、C 发送 | B 就绪，同房间三个上游的观众收到同一消息标识和序号，同一 Ticket 只能兑换一次。 |
| 同账号同时请求不同实例 | Redis 只允许一个新预约，额外请求按既有冷却规则返回 `429`。 |
| 停止当前持有目标分区的进程 | 其他实例接管该分区，受影响连接重连并刷新最近历史，回执和历史在任意健康实例可读。 |
| 历史先收到 103，后收到 101 | 103 先展示及落库。接口可先返回只有 103 的范围页。101 到达后写入且最大落库序号保持 103，下一次最近窗口刷新按序合并。 |
| 同分区房间 A 出现单条数据冲突，后续还有 A 和 B 的合法事件 | 保留原记录，日志定位到冲突记录的实际 offset。A、B 后续历史都继续推进。 |
| MySQL 提交失败或提交成功后 Kafka 位点未提交 | 从原位置重读。已经持久化的记录保持原值，有效新记录最终可读。 |
| 保留期边界跨过序号缺口 | 仅清理已到期记录。已到期的 Kafka 重放不重新入库，房间最大落库序号保持原值。 |
| 历史位点越界后接管 | 持久阻断随接管生效，目标分区的历史不可读，其他分区继续处理。 |
| 逐台摘流、排空、重启和回流 | 剩余实例承接新请求，已确认提交可查询，重连后应用同一消息时页面仅展示一次。 |
