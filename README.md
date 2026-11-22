# LiveFlow

LiveFlow 是基于 Java 的直播弹幕系统。第一阶段用一个 Spring Boot 应用承载 HTTP API、WebSocket 接入和消息消费，代码按认证、房间、实时连接和弹幕等业务模块组织。MySQL、Redis、Kafka 独立运行。Vue 前端负责直播间交互和弹幕渲染。

## 技术栈

| 层 | 选型 | 用途 |
| --- | --- | --- |
| 后端 | Java 21、Maven Wrapper 3.9.14、Spring Boot 4.1.3 | 用 Spring MVC 提供 HTTP API，由 Spring Boot 统一管理依赖版本 |
| 实时接入 | Spring WebSocket、原生 WebSocket、JSON | 连接管理、房间订阅、客户端消息收发 |
| 认证 | Spring Security、JWT Access Token、Redis 登录会话和 Refresh Token 轮换 | HTTP 鉴权、登录续期和单账户单登录 |
| 数据 | MySQL 8.4.13、MyBatis-Plus 3.5.18、Flyway | 保存用户、房间和历史弹幕，管理数据库变更 |
| 分布式状态 | Redis 8.2.11、Spring Data Redis | 跨实例广播、认证状态，后续用于在线统计和限流 |
| 消息 | Apache Kafka 4.3.2（KRaft）、Spring for Apache Kafka | 接收弹幕事件，供广播和持久化消费组分别消费 |
| 前端 | Node.js 24、npm、Vue 3.5、TypeScript、Vite 8 | 开发代理、类型检查和浏览器客户端 |
| 本地运维 | Docker Compose、Spring Boot Actuator | 启动基础依赖并检查服务和后端连接状态 |

Spring Boot 3.5 已结束开源支持，项目使用 Spring Boot 4.1 和对应的 MyBatis-Plus Boot 4 starter。 [Spring Boot 3.5 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) · [MyBatis-Plus 安装说明](https://baomidou.com/en/getting-started/install/)

跨实例阶段的计划链路为 `WebSocket 接入 → Kafka`。广播消费组把事件发到 Redis Pub/Sub，各实例将消息推给本机房间连接。持久化消费组把事件批量写入 MySQL，供历史查询。HTTP 认证依赖 MySQL、Redis 和 Flyway。Prometheus、Grafana 和 Nginx 随压测和部署阶段接入。

## 模块设计

后端由 [Maven 父 POM](backend/pom.xml) 聚合三个模块，统一管理依赖版本和构建配置。

| 模块 | 职责 |
| --- | --- |
| `liveflow-core` | 业务服务、会话存储接口、MyBatis Mapper、Flyway 迁移脚本 |
| `liveflow-infrastructure` | Redis 会话存储及 Lua 脚本、Kafka 健康检查，依赖 `core` |
| `liveflow-api` | MVC 控制器、HTTP 安全、WebSocket 接入和应用配置，依赖另外两个模块并打包可执行 JAR |

- [登录认证 V1](docs/design/auth/design.md) 涵盖 Spring MVC 分层、HTTP 接口、MySQL 用户索引、Redis 单会话和凭证轮换。WebSocket Ticket 归入后续实时连接阶段。

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

本阶段从已有账号登录。用户表由后端启动时的 Flyway 迁移创建。在本地或测试库中准备 ASCII 小写用户名和 Argon2id 哈希。可用 `backend` 中的 Maven Wrapper 生成运行依赖清单，再启动 JShell 读取测试密码并生成哈希。

```bash
./mvnw -pl liveflow-core dependency:build-classpath -Dmdep.outputFile=target/auth-classpath
jshell --execution local --class-path "$(cat liveflow-core/target/auth-classpath)"
```

在 JShell 输入以下语句，复制输出的哈希。密码通过终端读取，避免写入命令历史。

```java
var password = System.console().readPassword("测试密码：");
var encoder = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 19456, 2);
System.out.println(encoder.encode(java.nio.CharBuffer.wrap(password)));
java.util.Arrays.fill(password, '\0');
/exit
```

在本地 MySQL 客户端执行以下 SQL，将哈希占位符替换为实际生成的值。然后用此用户名和测试密码在 Vue 页面登录。

```sql
INSERT INTO users (username, password_hash, created_at, updated_at)
VALUES ('local_viewer', '<Argon2id 哈希>', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
```

同一账号的新登录会替换原会话。可以用两个独立浏览器会话观察替换行为，用同一个浏览器的两个标签页观察刷新与退出协调。生产环境以 HTTPS 部署并注入独立的 Base64 随机签名密钥。回环 HTTP 覆盖只适用于 `local` profile。

登录限流采用 60 秒固定窗口，每个规范化账号 10 次，每个连接来源 60 次。当前来源以服务端看到的连接地址为准。反向代理部署时，应按实际受信代理边界配置来源地址解析。

在本地 Compose 依赖健康时，可从仓库根目录运行认证集成测试。测试会创建并清理专用账号，连接配置沿用后端的本地默认值或当前终端覆盖值。

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
