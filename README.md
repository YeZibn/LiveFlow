# LiveFlow

LiveFlow 是基于 Java 的直播弹幕系统。第一阶段用一个 Spring Boot 应用承载 HTTP API、WebSocket 接入和消息消费，代码按认证、房间、实时连接和弹幕等业务模块组织。MySQL、Redis、Kafka 独立运行。Vue 前端负责直播间交互和弹幕渲染。

## 技术栈

| 层 | 选型 | 用途 |
| --- | --- | --- |
| 后端 | Java 21、Maven Wrapper 3.9.14、Spring Boot 4.1.3 | 用 Spring MVC 提供 HTTP API，由 Spring Boot 统一管理依赖版本 |
| 实时接入 | Spring WebSocket、原生 WebSocket、JSON | 连接管理、房间订阅、客户端消息收发 |
| 认证 | Spring Security、JWT Access Token、Redis 登录会话和 Refresh Token 轮换 | HTTP 鉴权、登录续期和单账户单登录 |
| 数据 | MySQL 8.4.13、MyBatis-Plus 3.5.18、Flyway | 保存用户、房间和历史弹幕，管理数据库变更 |
| 分布式状态 | Redis 8.2.11、Spring Data Redis | 房间频道广播、认证状态和限流 |
| 消息 | Apache Kafka 4.3.2（KRaft）、Spring for Apache Kafka | 接收弹幕事件，供广播和持久化消费组分别消费 |
| 前端 | Node.js 24、npm、Vue 3.5、TypeScript、Vite 8 | 开发代理、类型检查和浏览器客户端 |
| 本地运维 | Docker Compose、Spring Boot Actuator | 启动基础依赖并检查服务和后端连接状态 |

Spring Boot 3.5 已结束开源支持，项目使用 Spring Boot 4.1 和对应的 MyBatis-Plus Boot 4 starter。 [Spring Boot 3.5 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) · [MyBatis-Plus 安装说明](https://baomidou.com/en/getting-started/install/)

当前弹幕使用 V2 链路。HTTP 提交在 Kafka 确认入队后返回 202，Kafka Streams 为各房间分配连续序号，Redis Pub/Sub 转发实时弹幕，MySQL 保存历史供浏览器按序补拉。V2 首轮拓扑为单个 LiveFlow 进程。HTTP 认证依赖 MySQL、Redis 和 Flyway。 [弹幕 V2 设计](docs/design/room-messages/v2/design.md)

## 模块设计

后端由 [Maven 父 POM](backend/pom.xml) 聚合三个模块，统一管理依赖版本和构建配置。

| 模块 | 职责 |
| --- | --- |
| `liveflow-core` | 业务服务、会话存储接口、MyBatis Mapper、Flyway 迁移脚本 |
| `liveflow-infrastructure` | Redis 会话和弹幕预约、Kafka Streams 裁决与消费组、Redis 房间订阅，依赖 `core` |
| `liveflow-api` | MVC 控制器、HTTP 安全、WebSocket 接入和应用配置，依赖另外两个模块并打包可执行 JAR |

- [账号注册与登录认证 V1](docs/design/auth/design.md) 涵盖账号注册、密码规则、区分大小写的账号索引、Redis 单会话和凭证轮换。
- [个人主页 V1](docs/design/profile/design.md) 涵盖个人主页入口、昵称与头像等资料的编辑流程、本人资料接口和 MySQL 存储。
- [直播间 V1](docs/design/room/design.md) 定义一人一房、统一列表与名称搜索、UUID 进入、观看页布局，以及对应的 HTTP 接口和数据库索引。
- [直播间弹幕 V1](docs/design/room-messages/design.md) 定义单实例分发、WebSocket Ticket、HTTP 提交、短期去重，以及显示发送者和秒级本地时间的弹幕列表。
- [直播间弹幕 V2](docs/design/room-messages/v2/design.md) 定义 Kafka 异步受理、Redis 房间频道、MySQL 历史、发送回执和有序补拉。
- [单实例限流设计](docs/design/resilience/design.md) 定义登录、注册限流边界与弹幕账号 10 秒冷却。

## 本地启动

需要 Docker Compose v2 或兼容的新版、JDK 21 和 Node.js 24。应用和前端直接在宿主机上启动。

在仓库根目录启动基础设施。

```bash
docker compose up -d --wait
docker compose ps
```

另开终端，从仓库根目录启动后端。

```bash
# 首次本地启动时生成密钥。需要跨重启保留凭证时，从私有配置注入同一密钥。
export LIVEFLOW_JWT_SIGNING_KEY="$(openssl rand -base64 32)"
export LIVEFLOW_LOCAL_SECURE_COOKIE=false
cd backend
./mvnw -pl liveflow-api -am package
java -jar liveflow-api/target/liveflow-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

如果本机的 JDK 放在仓库的 `.devtools/jdk-21` 中，可先在仓库根目录执行 `export JAVA_HOME="$PWD/.devtools/jdk-21/Contents/Home"`。该路径适用于解压后的 macOS Temurin JDK，`.devtools` 已被 Git 忽略。

再开一个终端，从仓库根目录启动前端。

```bash
cd frontend
npm ci
npm run dev
```

打开 [LiveFlow 登录页](http://127.0.0.1:5173/)。[开发环境检查页](http://127.0.0.1:5173/dev) 通过 Vite 代理访问 Actuator 和 WebSocket。正常时 Spring Boot、MySQL、Redis、Kafka、WebSocket 都会显示 `UP`。后端也可直接检查。

房间弹幕的 WebSocket 在 `local` 模式下默认允许 `http://127.0.0.1:5173` 和 `http://localhost:5173`。更换前端端口或通过反向代理部署时，可设置 `LIVEFLOW_ROOM_MESSAGES_ALLOWED_ORIGIN`，多个地址用逗号分隔。每个值须与浏览器的 Origin 一致，使用非默认端口时需写出端口。

```bash
curl --fail http://127.0.0.1:18080/actuator/health
```

`local` profile 启用 `/ws/smoke`，收到文本 `ping` 后返回 `pong`，并在健康接口显示各依赖状态。默认 profile 仅公开整体健康状态。

| 服务 | 默认宿主机地址 |
| --- | --- |
| 后端 | `127.0.0.1:18080` |
| 前端 | `127.0.0.1:5173` |
| MySQL | `127.0.0.1:13306` |
| Redis | `127.0.0.1:16379` |
| Kafka | `127.0.0.1:19092` |

默认端口和 MySQL 凭据用于本地开发。需要调整时复制 `.env.example` 为 `.env` 并修改。Compose 会读取 `.env`，后端和前端终端需要同时导入改动的变量，例如在仓库根目录执行 `set -a; . ./.env; set +a`。MySQL 已初始化的数据卷会保留旧的数据库和账户配置。

## 认证联调

启动前后端后，打开 Vue 登录页，点击「注册」，输入账号和两次一致的密码。注册成功后页面预填账号名，使用刚设置的密码登录。账号严格区分大小写。新密码为 10–25 个可见 ASCII 字符，并同时包含大写字母、小写字母、数字和符号。使用旧测试数据的环境可按重建流程切换。 [注册接口](docs/design/auth/api.md#注册) · [测试数据重建](docs/design/auth/database.md#开发与测试数据重建)

同一账号的新登录会替换原会话。可以用两个独立浏览器会话观察替换行为，用同一个浏览器的两个标签页观察刷新与退出协调。生产环境以 HTTPS 部署并注入独立的 Base64 随机签名密钥。回环 HTTP 覆盖只适用于 `local` profile。

登录限流采用 60 秒固定窗口，每个原样账号 10 次，每个连接来源 60 次。注册独立计数，默认每个来源 600 秒内 10 次。两个入口超限都返回 `Retry-After`。可用 `LIVEFLOW_REGISTRATION_WINDOW_SECONDS` 和 `LIVEFLOW_REGISTRATION_MAX_ATTEMPTS` 配置注册额度。当前来源以服务端看到的连接地址为准。反向代理部署时，应按实际受信代理边界配置来源地址解析。

在本地 Compose 依赖健康时，可从仓库根目录运行认证集成测试。测试会创建并清理专用账号，连接配置沿用后端的本地默认值或当前终端覆盖值，Redis 使用测试数据库 15。

```bash
(cd backend && LIVEFLOW_AUTH_INTEGRATION=true ./mvnw clean verify)
```

本地联调覆盖了登录、页面重载、双标签页会话协调、退出和开发探测。浏览器中的过期、迟到响应和续期结果不明确通过本地故障注入复现。后端用签名正确但已过期的 JWT 测试实际过期判定，认证依赖故障在仓储边界模拟。

## 构建与停机

```bash
(cd backend && ./mvnw clean verify)
(cd frontend && npm ci && npm run build)
```

在相应终端按 `Ctrl+C` 停止前后端。在仓库根目录执行 `docker compose down` 停止基础设施，数据保留在命名卷中。
