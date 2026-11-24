# 单实例限流部署与运行设计

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

原弹幕 `rate-window-millis` 与 `rate-limit` 配置在切换时撤下，冷却使用上表参数。现有租约、回执和 Kafka 配置继续按 [弹幕 V2 运行设计](../room-messages/v2/deployment-and-operations.md) 管理。

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

计时器 `liveflow.rate.limit` 记录 Redis 限流裁决耗时，计数可按标签汇总。标签 `policy` 为 `login`、`registration` 或 `room_messages`，`outcome` 为 `allowed`、`limited` 或 `unavailable`。弹幕放行计数针对新预约，既有预约的重试不重复统计。Redis 不可用时返回对应的服务不可用错误，禁止在无法核对额度或预约时继续写入。

压测若沿用目标新预约速率，在默认冷却下按每账号每 10 秒最多一次分配账号。持续 100 条/秒至少需要约 1,000 个活跃发送账号，持续 20 条/秒至少约 200 个，测试还需留出调度余量。既有弹幕 V2 压测记录对应发布时的旧规则。 [弹幕 V2 压测口径](../room-messages/v2/deployment-and-operations.md#容量与观测)
