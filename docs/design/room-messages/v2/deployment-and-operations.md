# 直播间弹幕 V2 部署与运行设计

## 部署拓扑

现行本地流程运行一个 Spring Boot 进程，同进程启用 MVC、WebSocket、Kafka Producer、Kafka Streams、三个输出消费组和 Redis 订阅调度。三实例方案沿用同一 JAR 与这些职责。Topic 显式创建，Streams 维护内部状态 Topic。单 Broker 环境使用一副本和最小同步副本数 1。 [本地 Compose](../../../../compose.yaml) · [Topic 约定](event-contract.md#topic-和消费组)

| 环境 | Kafka 与状态 | 数据与接入 |
| --- | --- | --- |
| 本地与单实例压测 | 1 个 Broker，业务和内部 Topic 1 副本；Streams 初始使用 1 个线程 | 本机 Compose；Vite 转发 `/api` 与 `/ws`，使用已配置的浏览器来源 |
| 三实例联调 | 3 个应用 JVM 共享既有 Topic、消费组和 6 个分区，各自持有 Streams 状态目录 | 同源代理连接三个后端，统一 Redis、MySQL 和 JWT 签名配置 |
| 生产部署基线 | 3 副本、至少 2 个同步副本；业务 Producer `acks=all`，Streams 状态目录使用持久卷或按 changelog 重建 | 同源 TLS 代理，保留升级与空闲连接超时设置；MySQL 与 Redis 采用独立资源配额 |

生产 Redis 如共用认证与弹幕业务，应分别观测命令延迟和连接预算。Pub/Sub 使用独立连接，避免订阅生命周期占用普通命令连接。生产 Kafka 的认证和 Redis 环境前缀由部署配置提供。 [Kafka Topic 参数](https://kafka.apache.org/43/configuration/topic-configs/) · [Redis Pub/Sub 环境隔离](https://redis.io/docs/latest/develop/pubsub/#database--scoping) · [现有本地启动说明](../../../../README.md#本地启动)

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
| 历史保留与清理 | 写入后 24 小时，按房间连续前缀每批最多 500 条 | 持续平均消息量、磁盘余量与历史保障周期 |
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
3. 查看 `/actuator/health` 和前端开发检查。排查历史积压时使用以下命令查看每分区进度，再结合 MySQL 房间连续水位判断。

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

### V2 单实例切换到三实例

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
| 单实例异常退出 | 入口移出该实例。健康实例继续接收 HTTP 和 WebSocket，Kafka 重新分配原任务。受影响观众重新申请 Ticket 并按原游标补拉。 |
| 单实例 Streams 客户端异常 | 关闭本机 Streams 客户端并告警。健康实例接管；确认持续故障时记录受影响分区并保留位点。 |
| Redis 发布暂时失败 | 广播组暂停受影响分区并退避，继续维持消费者心跳。恢复后按顺序重放；超过 10 秒新鲜窗口的事件推进广播组进度，历史组持续处理。网关的历史水位通知触发补拉。 |
| Redis 整体不可用 | 认证、Ticket、提交限流、回执投影和广播可能同时受影响。已正式提交的 Kafka 事件保留，历史消费可单独继续。服务恢复后按 Kafka 重建未过期回执。 |
| MySQL 无法写入 | 历史组保留消费位置，持续观测落库水位与差值。已连接且已建立连续基线的客户端可继续展示连续实时弹幕。需要读取房间和资料的新提交可能因 MySQL 失败被拒绝。 |
| 积压接近 Kafka 保留期、落库序号跳号或恢复了旧库快照 | 暂停相关消费进度，确认各房间连续落库水位与可重放 offset；优先延长保留、恢复备份并补放缺失段。缺失范围无法恢复时，将受影响房间的历史标记为暂不可用，保持原水位，待明确数据修复方案后再开放。 |
| 应用重启或网关失去 Redis 订阅 | 重新申请 Ticket 或恢复活跃房间订阅，按客户端连续游标补拉。回执可以从原 Topic 恢复。如需主动触发完整回执窗口重建，移除当前环境的 `<env>:liveflow:rooms:message:receipt-projection:<Kafka 命名空间或 main>` 标记。 |

持久阻断修复先查询 [阻断记录](database.md#处理阻断记录)，核对职责组、Topic、分区、房间、故障位置和 `revision`。对位点越界先恢复数据或确认可验证的新位置。调整 Kafka 消费位点时先退出相关消费组的所有成员，按 Kafka 的消费组操作流程应用修复后的位点。确认 MySQL 水位和可重放记录后，按完整主键和已核验的 `revision` 清除该条阻断，重新接入并确认分区进度推进。

## 容量与观测

分别观察 HTTP → Kafka 确认、Kafka 确认 → 正式接收、正式接收 → 广播、正式接收 → MySQL 提交。按 P50、P95、P99 记录延迟；同时记录按分区的积压数量和年龄、回执未确认率、Redis 发布失败、慢连接关闭、出站字节和补拉次数。指标标签使用环境、阶段、消费组和分区等有界维度。房间和提交标识用于故障日志中的定位，上下文不含登录凭证或原始正文。

单实例压测从 512 条连接逐步升至 1,000 条。普通房间以总发送 100 条/秒为起点，按默认 10 秒冷却至少分散到约 1,000 个有效发送账号，配置额外账号余量。热点场景以一间房 1,000 条连接、20 条弹幕/秒为起点，至少约 200 个有效发送账号。弹幕 V2 已归档的压测按当时每账号 10 秒 5 条的规则执行。记录请求数、Kafka 已确认提交数、正式接收数与 WebSocket 交付次数。历史容量使用持续平均流量估算，每秒持续 100 条约为每天 864 万条，生产保留值由可用磁盘、业务需求和滞后恢复预算共同确定。

三实例测试分别覆盖单房间、多房间、三台全量运行、掉一台后只剩两台，以及滚动发布。发压分开记录 Kafka 已确认、正式接收、Redis 广播、客户端唯一交付及 MySQL 入库。容量以剩余两台在目标负载下仍可维持的连接、发送延迟、交付缺失与积压收敛能力为准。记录重新分配期间最长停止推进时长、掉线人数、重新就绪耗时和补拉收敛时间。故障期间已获 `202` 的提交应按正常回执规则最终可查询，历史重放后的 `roomSeq` 连续且无重复。

| 演练场景 | 观察到的目标行为 |
| --- | --- |
| A 发 Ticket、B 兑票、C 发送 | B 就绪，同房间三个上游的观众收到同一消息标识和序号，同一 Ticket 只能兑换一次。 |
| 同账号同时请求不同实例 | Redis 只允许一个新预约，额外请求按既有冷却规则返回 `429`。 |
| 停止当前持有目标分区的进程 | 其他实例接管该分区，受影响连接重连并补齐，回执和历史在任意健康实例可读。 |
| 历史位点越界或房间历史冲突后接管 | 阻断记录随接管继续生效，受影响的历史不可读，其他范围继续按各自水位处理。 |
| 逐台摘流、排空、重启和回流 | 剩余实例承接新请求，已确认提交可查询，重连后应用同一消息时页面仅展示一次。 |
