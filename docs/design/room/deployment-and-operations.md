# 房间存在性缓存部署与运行

## 部署拓扑

本轮增加一个独立的缓存 Redis，由同一环境中的 LiveFlow 应用共享。缓存客户端持有独立连接及超时配置。当前应用和业务 Redis 继续按现有拓扑启动，缓存可从 MySQL 按需重建。缓存服务应为独立进程或托管实例，使用自己的内存配额和凭证。 [房间缓存规则](storage.md) · [现有三实例拓扑](../room-messages/v2/deployment-and-operations.md#三实例部署)

联调通过 Compose 增加独立缓存服务，沿用仓库的 Redis 镜像基线。初始配置使用 `maxmemory 64mb` 和 `maxmemory-policy allkeys-lfu`，以空缓存启动，按需回填。设置容器内存限额时给 Redis 进程和客户端开销留出空间。仅联调宿主机进程需要访问时绑定回环地址。业务 Redis 仍保存认证、限流和弹幕状态。 [当前 Compose 配置](../../../compose.yaml) · [Redis 淘汰和内存预算](https://redis.io/docs/latest/develop/reference/eviction/)

## 配置与生效

下表是实施时的配置约定。应用启动时装载配置，改变运行模式或额度时逐台滚动生效。

| 配置项 | 初值或约定 |
| --- | --- |
| `liveflow.rooms.cache.enabled` | 默认 `false`。设为 `true` 读写缓存 Redis，设为 `false` 走受控 MySQL 回源。 |
| `liveflow.rooms.cache.redis` | 单独注入地址及凭证。启用时校验配置完整性。连接或命令运行失败时进入受控回源。 |
| `liveflow.rooms.cache.redis.uri` | 本地默认 `redis://127.0.0.1:16380`。使用 `LIVEFLOW_ROOM_CACHE_REDIS_URI` 注入完整连接地址，或在本地用 `LIVEFLOW_ROOM_CACHE_PORT` 同时覆盖 Compose 映射和默认连接端口。 |
| `liveflow.rooms.cache.redis.connect-timeout`、`command-timeout` | 初始分别为 250 和 100 毫秒。启用后提前异步建连，应用仍按现有规则报告就绪。 |
| `liveflow.rooms.cache.environment` | 同一部署环境使用相同值。联调使用独立命名空间。 |
| `liveflow.rooms.cache.positive-ttl`、`negative-ttl`、`fill-lease` | 取值与计时起点由缓存规则定义。启动时校验 TTL 范围及租约为正值。 [Key 和生命周期](storage.md#key-和生命周期) |
| `liveflow.rooms.cache.wait-timeout` | 150 毫秒。等待预算包含复查 Redis 的命令耗时，单次命令须受剩余预算约束。 |
| `liveflow.rooms.cache.source-rate`、`source-burst`、`source-concurrency` | 每实例分别为 30 次/秒、6 次、4 条在途。缓存启用、故障和旁路共用。 [回源规则](storage.md#缓存故障与回源预算) |

缓存 Redis 命令使用独立且短于回填等待预算的超时，连接或重连期间避免无界排队。运行时缓存状态参与监控，本机入口就绪沿用现有检查。MySQL 获取连接和执行查询的耗时分别观测，租约到期后按令牌保护写入。

本地 `docker compose up -d --wait` 会启动 `room-cache`。应用默认走旁路。需要验证缓存时，在启动后端前设置 `LIVEFLOW_ROOM_CACHE_ENABLED=true`，同环境实例使用同一缓存连接配置和命名空间。

## 发布与回退

1. 启动独立缓存 Redis，核对地址、命名空间、容量和网络权限。该变更复用现有查询和索引，首次运行按请求回填。
2. 逐台部署带回源预算的新应用，再逐台启用缓存。用同一个房间跨实例验证命中、回执和历史读取。统计整组三台的回源上限时，以三台都已部署回源预算为前提。
3. 启用后观察 MySQL 查询量、连接池等待和受控拒绝。缓存不稳定时，采用有上限的回源。需要人工旁路时逐台关闭缓存开关，保留回源预算。若临时退回旧版本，旧进程的房间存在性查询会直接查库，按回退前的 MySQL 余量控制流量。
4. 缓存重启或切回启用时从冷 Key 按需回填。旧缓存 Key 按原 TTL 或缓存淘汰规则清理。

## 联调与观测

| 信号 | 记录与判断 |
| --- | --- |
| 缓存结果 | 分别记录正命中、负命中、未命中、缓存读写失败。 |
| 回填 | 统计取得租约、复用结果、等待超时、失去租约和创建后预热失败。 |
| 回源 | 使用 `liveflow.rooms.existence` 记录开始和拒绝次数，`liveflow.rooms.existence.source.inflight` 记录在途数，`liveflow.rooms.existence.source.duration` 记录包含取连接在内的回源耗时。连接池等待从 Hikari 指标单独读取。聚合统计时加总三台实例。 |
| 接口与依赖 | 比较命中、回源和故障期间的 HTTP 延迟与状态。业务 Redis 和缓存 Redis 分别观察命令延迟、错误及内存压力。 |

指标按操作、结果和实例聚合。UUID 和账号 ID 不作为指标标签。联调沿用基线负载，分开统计存在性、发送者资料、历史阻断、水位和历史页 SQL。仅在隔离的 MySQL 环境打开查询日志，按应用账号和 SQL 形状统计组前组后的执行次数。停止新增缓存 Redis 后核对业务 Redis 仍可用、预算拒绝符合预期、恢复后可以重新回填。 [基线与对照场景](storage.md#基线与对照场景)
