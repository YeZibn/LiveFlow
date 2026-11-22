# LiveFlow

LiveFlow 是基于 Java 的直播弹幕系统。第一阶段用一个 Spring Boot 应用承载 HTTP API、WebSocket 接入和消息消费，代码按认证、房间、实时连接和弹幕等业务模块组织。MySQL、Redis、Kafka 独立运行。Vue 前端负责直播间交互和弹幕渲染。

## 技术栈

| 层 | 选型 | 用途 |
| --- | --- | --- |
| 后端 | Java 21、Maven Wrapper 3.9.14、Spring Boot 4.1.3 | 用 Spring MVC 提供 HTTP API，由 Spring Boot 统一管理依赖版本 |
| 实时接入 | Spring WebSocket、原生 WebSocket、JSON | 连接管理、房间订阅、客户端消息收发 |
| 认证 | Spring Security、JWT Access Token、Redis Refresh Token 状态和一次性 WebSocket Ticket | HTTP 鉴权、登录续期和 WebSocket 握手认证 |
| 数据 | MySQL 8.4.13、MyBatis-Plus 3.5.18、Flyway | 保存用户、房间和历史弹幕，管理数据库变更 |
| 分布式状态 | Redis 8.2.11、Spring Data Redis | 跨实例广播、认证状态，后续用于在线统计和限流 |
| 消息 | Apache Kafka 4.3.2（KRaft）、Spring for Apache Kafka | 接收弹幕事件，供广播和持久化消费组分别消费 |
| 前端 | Node.js 24、npm、Vue 3.5、TypeScript、Vite 8 | 开发代理、类型检查和浏览器客户端 |
| 本地运维 | Docker Compose、Spring Boot Actuator | 启动基础依赖并检查服务和后端连接状态 |

Spring Boot 3.5 已结束开源支持，项目使用 Spring Boot 4.1 和对应的 MyBatis-Plus Boot 4 starter。 [Spring Boot 3.5 发布说明](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) · [MyBatis-Plus 安装说明](https://baomidou.com/en/getting-started/install/)

跨实例阶段的计划链路为 `WebSocket 接入 → Kafka`。广播消费组把事件发到 Redis Pub/Sub，各实例将消息推给本机房间连接。持久化消费组把事件批量写入 MySQL，供历史查询。当前底座已接入 MySQL、Redis、Kafka 和 Flyway，认证与弹幕链路留到业务阶段实现。Prometheus、Grafana 和 Nginx 随压测和部署阶段接入。

## 本地启动

需要 Docker Compose v2 或兼容的新版、JDK 21 和 Node.js 24。应用和前端直接在宿主机上启动。

在仓库根目录启动基础设施。

```bash
docker compose up -d --wait
docker compose ps
```

另开终端，从仓库根目录启动后端。

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

如果本机的 JDK 放在仓库的 `.devtools/jdk-21` 中，可先在仓库根目录执行 `export JAVA_HOME="$PWD/.devtools/jdk-21/Contents/Home"`。该路径适用于解压后的 macOS Temurin JDK，`.devtools` 已被 Git 忽略。

再开一个终端，从仓库根目录启动前端。

```bash
cd frontend
npm ci
npm run dev
```

打开 [开发环境检查页](http://127.0.0.1:5173)，它通过 Vite 代理访问 Actuator 和 WebSocket。正常时 Spring Boot、MySQL、Redis、Kafka、WebSocket 都会显示 `UP`。后端也可直接检查。

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

## 构建与停机

```bash
(cd backend && ./mvnw clean verify)
(cd frontend && npm ci && npm run build)
```

在相应终端按 `Ctrl+C` 停止前后端。在仓库根目录执行 `docker compose down` 停止基础设施，数据保留在命名卷中。
