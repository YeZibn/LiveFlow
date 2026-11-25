# 单实例限流与弹幕发送熔断设计

本文定义单实例的认证限流、弹幕账号冷却和已确认的弹幕发送熔断方案。熔断覆盖所有房间的弹幕发送 POST，从发送业务入口保护 MySQL 查询、Redis 预约和 Kafka 入队。直播间页面、WebSocket 收件、历史和回执查询按各自的依赖状态提供服务。 [认证设计](../auth/design.md) · [弹幕 V2 设计](../room-messages/v2/design.md)

三实例仍在各进程独立维护发送断路器、熔断窗口和半开许可。登录与注册限流、弹幕账号冷却由共享 Redis 裁决。新提交触发的分区接入拒绝属于预先拒绝，释放本次许可并避免作为一次实际依赖失败计数。来源 IP 在受信代理边界处理。 [三实例部署](../room-messages/v2/deployment-and-operations.md#三实例部署)

## 技术选型与职责

沿用 Java 21、Spring Boot MVC、Spring Data Redis、Redis Lua、MySQL、Kafka 和 Vue。以 `resilience4j-circuitbreaker` 2.4.0 核心库程序化接入发送入口，由 Resilience4j 管理窗口、状态和半开许可。固定窗口与账号冷却继续使用 Redis Lua 原子裁决。 [Maven 父 POM](../../../backend/pom.xml) · [Resilience4j 2.4.0](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0)

| 层 | 职责 |
| --- | --- |
| `liveflow-core` | 定义认证限流和发送熔断接口。在 `RoomMessageService.send()` 中取得许可，按依赖实际结果回报。认证服务显式调用认证限流，`AuthSessionStore` 负责会话读写。 |
| `liveflow-infrastructure` | 接入 Resilience4j，维护进程内断路器及异步回报归属。Redis 脚本原子裁决账号限流和提交预约。Kafka 适配器区分实际依赖故障、未就绪、主动停写与本机容量拒绝。 |
| `liveflow-api` | 校验认证、CSRF 和请求字段，使用可信来源及账号标识，组装响应和可用的回执信息。 |
| Vue | 每次用户操作最多发出一次发送 POST，按实际结果展示草稿、账号冷却或回执查询。 |

## 业务规则

| 策略 | 主体与算法 | 默认值与计数点 |
| --- | --- | --- |
| 登录 | 原样账号名和可信连接来源 IP，分别使用固定窗口 | 60 秒内账号 10 次、来源 60 次。请求字段有效后、密码校验前共同计数。 |
| 注册 | 可信连接来源 IP，使用独立固定窗口 | 600 秒内 10 次。请求字段有效后、计算密码哈希前计数。 |
| 弹幕 | 当前认证账号 ID，使用冷却键。各房间、标签页和登录会话共享 | 两次新预约至少间隔 10 秒，在首次创建预约时开始冷却。 |

## 端到端流程

登录先校验字段。脚本对账号及来源两个维度一起判定，全部通过才递增。账号名保留原拼写。注册脚本只使用注册额度；格式合法的重复注册依然计入尝试，账号唯一性由 MySQL 裁决。 [认证数据库](../auth/database.md)

弹幕通过鉴权与输入检查后，先向发送断路器申请许可，再读取提交状态、检查房间与发送者、执行 Redis 预约。首次预约同时授予一次 Kafka 发布许可和账号冷却。首次许可在 `12:00:00.250` 时，下一次新许可最早为 `12:00:10.250`。同标识同正文的重复请求只读取既有结果，同标识异文返回冲突。已建立的冷却按原截止时间到期，含生产失败或结果未确认的情形。 [弹幕 V2 接收流程](../room-messages/v2/design.md) · [Redis 预约裁决](../room-messages/v2/storage.md#账号额度和提交绑定)

## 发送熔断

单进程内各房间共用一个断路器。在正文规范化后、首次发送专用 Redis 或 MySQL 访问前检查许可。熔断或半开名额用完时返回 `503 ROOM_MESSAGES_UNAVAILABLE`，本次请求在查询房间、建立预约和扣占账号冷却前结束。已取得许可的请求仍按实际结果完成。Kafka Producer 保持自己的就绪检查和并发上限。 [发送错误](../room-messages/v2/api.md#http-错误) · [熔断参数](deployment-and-operations.md#弹幕发送熔断配置)

| 状态 | 准入与切换 |
| --- | --- |
| 正常 `CLOSED` | 最近 10 秒至少完成 30 个有效样本，失败率达到 20% 或慢调用率达到 40% 即进入暂停。超过 800 毫秒的有效样本同时计慢调用。 |
| 暂停 `OPEN` | 首次暂停 5 秒，连续重新打开按 10、20、30 秒递增，30 秒封顶。等待期过后，首个通过鉴权与参数校验的发送请求触发半开。 |
| 半开 `HALF_OPEN` | 放行 5 个有效验证，半开窗口最多 60 秒。5 条都获 Broker 确认且至多 1 条慢调用时恢复正常；至少 1 条依赖失败、至少 2 条慢调用，或窗口到期未收齐时重新暂停。业务拒绝归还本次许可，窗口继续计时。 |

### 样本与回报归属

| 发送结果 | 回报规则 |
| --- | --- |
| 新弹幕获得 Kafka Broker 确认 | 记一次成功。Redis 入队状态写回失败单独记录，Broker 确认仍可返回 `202`。 |
| MySQL 查房间或发送者、Redis 查绑定或申请预约出现依赖故障，Kafka 正式接入未就绪或生产确认失败 | 记一次依赖失败。按实际发生阶段归类，HTTP 错误码用于对外表达结果。 |
| 无效参数、鉴权失败 | 在许可前拒绝。 |
| `429`、房间不存在、消息冲突、读取到已有预约结果、主动停写、本机并发额度耗尽 | 已取许可时归还，跳过成功与失败统计。 |
| HTTP 提前超时或浏览器断开 | HTTP 按结果未确认处理。实际依赖完成后按原请求的结果回报。 |

时长从取得许可算到实际依赖结果，Kafka 确认后的 Redis 状态写回单独计时。一个有效请求只回报一次，同时可以属于失败样本和慢调用样本。接入层在同一并发边界核对许可取得时的状态轮次和回报归属，避免旧请求的完成或归还影响新一轮半开。迟到的依赖结果仍按原预约处理和记录。 [Resilience4j 状态机实现](https://raw.githubusercontent.com/resilience4j/resilience4j/v2.4.0/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java)

## 专业文档

- [限流接口设计](api.md)定义限流响应与等待时间。[弹幕发送接口](../room-messages/v2/api.md#发送弹幕)定义单次提交、熔断响应和查询回执。
- [限流存储设计](storage.md)定义 Redis Key、Lua 裁决和到期规则。[弹幕数据库设计](../room-messages/v2/database.md#发送熔断的数据边界)说明持久数据边界。
- [部署与运行设计](deployment-and-operations.md)定义熔断参数、观测、发布和回退。
