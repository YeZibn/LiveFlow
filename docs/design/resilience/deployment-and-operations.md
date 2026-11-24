# 单实例限流与弹幕发送熔断运行设计

沿用单 LiveFlow 进程和现有 Redis、MySQL、Kafka 服务。应用启动时校验额度与时长均为正值。生产环境的连接来源需保留可信来源地址，同一环境的冷却命名沿用现有弹幕 Redis 前缀。 [现有部署配置](../../../backend/liveflow-api/src/main/resources/application.yaml)

## 配置

配置集中到 `liveflow.resilience.rate-limit`。以下为该前缀下的目标参数。

| 参数 | 默认值 | 含义 |
| --- | ---: | --- |
| `login.window-seconds` | 60 | 登录固定窗口时长 |
| `login.account-max-attempts` | 10 | 账号窗口上限 |
| `login.source-max-attempts` | 60 | 来源 IP 窗口上限 |
| `registration.window-seconds` | 600 | 注册固定窗口时长，继续支持 `LIVEFLOW_REGISTRATION_WINDOW_SECONDS` |
| `registration.max-attempts` | 10 | 注册来源上限，继续支持 `LIVEFLOW_REGISTRATION_MAX_ATTEMPTS` |
| `room-messages.cooldown-millis` | 10000 | 同账号两次新预约的最短间隔 |

原弹幕 `rate-window-millis` 与 `rate-limit` 配置在限流切换时撤下，冷却使用上表参数。预约、回执和 Kafka 运行参数见 [弹幕 V2 运行设计](../room-messages/v2/deployment-and-operations.md)。

## 弹幕发送熔断配置

目标配置前缀为 `liveflow.resilience.circuit-breaker.room-messages`，由应用配置绑定到 Resilience4j 核心库。按进程维护一个断路器。应用启动时校验时间窗、样本数、时长、百分比及退避范围，区分发布时选用的观测模式和执行模式。以下参数为首版校准起点。

| 应用参数 | 起始值 | Resilience4j 配置及语义 |
| --- | ---: | --- |
| `mode` | `observe` | 首轮使用 `METRICS_ONLY` 采集数据，经压测和故障演练后切换为 `enforce`，从 `CLOSED` 起运行。 |
| `sliding-window-seconds` | `10` | `slidingWindowType=TIME_BASED`，`slidingWindowSize=10`，按已完成的有效结果统计。 |
| `minimum-number-of-calls` | `30` | `minimumNumberOfCalls`。正常状态样本少于 30，按单次请求报告故障。 |
| `failure-rate-threshold` | `20` | `failureRateThreshold`，达到 20% 打开。 |
| `slow-call-duration-threshold` | `800ms` | `slowCallDurationThreshold`，严格超过该时长计慢。 |
| `slow-call-rate-threshold` | `40` | `slowCallRateThreshold`，达到 40% 打开。 |
| `open-initial-wait` / `open-max-wait` | `5s` / `30s` | `waitIntervalFunctionInOpenState`，倍率 2，上限 30 秒，依次为 5、10、20、30 秒。恢复后下一次从 5 秒起算。 |
| `half-open-permitted-calls` | `5` | `permittedNumberOfCallsInHalfOpenState`，以 5 个有效完成结果判定恢复。 |
| `half-open-max-wait` | `60s` | `maxWaitDurationInHalfOpenState`，从实际进入半开时起算。 |

`automaticTransitionFromOpenToHalfOpenEnabled=false`，暂停期满后由真实发送请求触发。`transitionToStateAfterWaitDuration=OPEN`，超时未收齐有效结果时进入下一档暂停。半开使用独立的计数窗口，失败率和慢调用率与正常状态共用阈值。 [Resilience4j 配置](https://resilience4j.readme.io/docs/circuitbreaker) · [半开统计实现](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.4.0/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerMetrics.java) · [退避函数](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.4.0/resilience4j-core/src/main/java/io/github/resilience4j/core/IntervalFunction.java)

800 毫秒用于判定一个已完成的依赖调用是否慢，60 秒用于等待一轮半开的 5 个有效结果。MVC 的响应等待、Redis 命令超时和 Kafka 交付超时分别管理。仅有一个账号活跃时，5 个新预约至少跨 40 秒，60 秒给账号冷却和依赖结果留出余量。

### 发布与回退

1. 暂停旧版发送 POST，退出会授予旧租约的后端，再同步发布后端和 Vue。新 `202` 移除 `retryUntil`，页面使用新的响应模型和单次发送入口。接入分区就绪后恢复发送，旧页面刷新加载本次发布的 Vue。已有预约、回执及账号冷却继续按其绝对截止到期。
2. 首轮用 `observe` 采集健康峰值和故障演练数据。检查发送 P95、P99、超过 800 毫秒的比例、失败阶段和有效样本数后，再启用 `enforce`。800 毫秒和 40% 优先按实测校准。
3. 熔断策略异常时切回 `observe`，保留本轮 API 及单次提交。涉及回退发布制品时，暂停发送并将前端和后端成对切换，按对应版本的发送契约恢复流量。

### 指标与演练

- 记录状态切换、入口拒绝、半开有效样本和许可归还、慢调用比例、实际依赖失败、HTTP 结果未确认及 Broker 确认后的 Redis 写回失败。指标按阶段和结果聚合，日志不记录凭证和正文。
- 在健康稳态、峰值和短突发下采集有效样本。按账号 10 秒冷却分配足够发送者，同时覆盖多房间及热点房间。分别对发送阶段的 MySQL、Redis 和 Kafka 注入失败及延迟，验证最低样本数、阈值等号、连续退避和恢复后的首轮间隔。
- 半开同时发起超过 5 个请求，检查有效验证次数、业务结果归还许可和 60 秒超时。分别覆盖 5 条都确认且至多 1 条慢、1 条依赖失败、2 条慢，以及上一轮回调迟到，确认四种结果各自正确。
- 复查一次用户发送只有一个 POST、同标识并发只有一次发布许可、入口短路不建立预约、Broker 已确认后 Redis 写回故障仍可返回 `202`，以及历史和回执只受各自依赖影响。 [发送接口](../room-messages/v2/api.md) · [弹幕容量基线](../room-messages/v2/deployment-and-operations.md#容量与观测)

## 发布与旧额度清理

1. 暂停旧版弹幕发送入口，确认旧进程已停止写入旧额度键。选择本次发布环境的 Redis 实例、逻辑库与既有 `<env>` 前缀。
2. 在选定环境中扫描并删除完整匹配 `<env>:liveflow:rooms:message:{<userId>}:rate`、且类型为 ZSET 的旧额度键。匹配主体限定为账号 ID，键名必须以 `:rate` 结尾。清理范围仅限此类弹幕旧额度，无需搬迁数据。
3. 恢复新版本的弹幕发送入口。首次合法新预约直接建立冷却。已有预约和回执、认证计数、用户与历史表、Kafka 数据和消费位点都保留。
4. 如需回退，先暂停新版发送再恢复旧程序。旧程序按原规则重新累积旧额度，已清理的短期计数从空窗口开始。回退沿用现有预约、回执和历史数据。

### 旧额度清理命令

停止旧进程后，在当前发布环境显式设置 `REDIS_HOST`、`REDIS_PORT`、`REDIS_DB`、`RATE_ENV` 并运行。默认仅预览，确认输出后以 `CLEANUP_MODE=apply` 重新运行同一段。Redis 凭证沿用运维环境的注入方式。

```bash
(
    set -euo pipefail
    : "${REDIS_HOST:?}" "${REDIS_PORT:?}" "${REDIS_DB:?}" "${RATE_ENV:?}"
    mode="${CLEANUP_MODE:-preview}"
    [[ "$RATE_ENV" =~ ^[A-Za-z0-9_-]{1,32}$ ]]
    [[ "$mode" == preview || "$mode" == apply ]]

    redis=(redis-cli -e --raw -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB")
    pattern="^${RATE_ENV}:liveflow:rooms:message:\\{[1-9][0-9]*\\}:rate$"
    candidates=$(mktemp)
    trap 'rm -f "$candidates"' EXIT
    "${redis[@]}" --scan --pattern "${RATE_ENV}:liveflow:rooms:message:*:rate" > "$candidates"
    while IFS= read -r key; do
        [[ "$key" =~ $pattern ]] || continue
        [[ "$("${redis[@]}" TYPE "$key")" == zset ]] || continue
        printf '%s %s\n' "$mode" "$key"
        if [[ "$mode" == apply ]]; then
            "${redis[@]}" UNLINK "$key" >/dev/null
        fi
    done < "$candidates"
)
```

## 可观测与故障处置

计时器 `liveflow.rate.limit` 记录 Redis 限流裁决耗时，计数可按标签汇总。标签 `policy` 为 `login`、`registration` 或 `room_messages`，`outcome` 为 `allowed`、`limited` 或 `unavailable`。弹幕放行计数针对新预约，既有预约查询不重复统计。Redis 不可用时返回对应的服务不可用错误，禁止在无法核对额度或预约时继续写入。

压测若沿用目标新预约速率，在默认冷却下按每账号每 10 秒最多一次分配账号。持续 100 条/秒至少需要约 1,000 个活跃发送账号，持续 20 条/秒至少约 200 个，测试还需留出调度余量。既有弹幕 V2 压测记录对应发布时的旧规则。 [弹幕 V2 压测口径](../room-messages/v2/deployment-and-operations.md#容量与观测)
