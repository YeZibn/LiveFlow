# 认证模块 MySQL 用户表

MySQL `users` 是账号和密码哈希的权威数据源。注册通过 `UserMapper` 新增账号，登录按精确的用户名读取密码哈希。会话、刷新和退出由 Redis 管理。 [注册流程](design.md#注册) · [Redis 会话](session.md)

## 表字段

| 字段 | 类型 | 约束与含义 |
| --- | --- | --- |
| `id` | `BIGINT` | `NOT NULL AUTO_INCREMENT`，主键，Java 使用 `Long`。HTTP JSON 和 JWT 中使用十进制字符串 |
| `username` | `VARCHAR(32)` | `NOT NULL`，字符集 `ascii`，排序规则 `ascii_bin`，按原始拼写保存和查找，严格区分大小写 |
| `password_hash` | `VARCHAR(255)` | `NOT NULL`，保存 Argon2id 编码结果 |
| `created_at` | `DATETIME(3)` | `NOT NULL`，账号创建时间，按 UTC 保存 |
| `updated_at` | `DATETIME(3)` | `NOT NULL`，账号最近更新时间，按 UTC 保存 |

应用按接口字段规则校验用户名，以原始值绑定 SQL 参数。`Alice` 和 `alice` 分别查找，对完全相同的用户名由唯一索引裁决。 [接口字段规则](api.md#请求字段) · [MySQL 二进制排序规则](https://dev.mysql.com/doc/refman/8.4/en/charset-binary-collations.html) · [OWASP 密码存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)

## 索引与查询

| 索引 | 定义 | 查询与用途 |
| --- | --- | --- |
| `PRIMARY` | `PRIMARY KEY (id)` | InnoDB 聚簇索引，`/me` 按可信用户 ID 等值查询 |
| `uk_users_username` | `UNIQUE KEY uk_users_username (username)` | 复用现有索引，支持精确登录查询和注册查重 |

注册功能启用后，账号表的目标结构如下。

```sql
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB;
```

代表性读写使用绑定参数。注册的时间列取同一写入时点，使用 UTC 毫秒精度。

```sql
INSERT INTO users (username, password_hash, created_at, updated_at)
VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
SELECT id, password_hash FROM users WHERE username = ?;
SELECT id, username FROM users WHERE id = ?;
```

登录查询通过用户名二级索引定位记录，再以 `id` 回表读取 `password_hash`。二级索引条目已包含主键 `id`，两个索引覆盖本阶段的账号读路径。 [InnoDB 聚簇索引与二级索引](https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html)

## 读写边界

- 登录和当前用户查询均为账号读取。会话的替换、续期和撤销在 Redis 提交。
- 注册在校验和限流通过后生成 Argon2id 哈希，再插入一行账号。MyBatis 取得生成的主键，事务提交后向前端返回该 ID 和账号名。个人资料在个人主页首次保存时创建。
- 并发注册完全相同的账号名时，一个请求可以插入成功，另一个因 `uk_users_username` 冲突返回 `409 AUTH_USERNAME_ALREADY_EXISTS`。

## 开发与测试数据重建

当前方案按开发和测试账号及关联资料可重建处理。实施切换时，先暂停目标环境的认证写入，按已核定的测试账号 ID 清理对应 Redis 会话，再删除这些账号。MySQL 外键会级联删除对应的个人资料。保留该批 ID 直到两边清理完成，复查账号、资料和会话均已清除，再执行后续迁移。清理范围按测试账号限定，保留数据库的自增序列。 [个人资料外键](../profile/database.md#表字段与索引) · [会话键格式](session.md#redis-数据结构)

保留现有 Flyway V1 和 V2。在 `liveflow-core` 的数据库迁移目录新增 `V3__make_username_case_sensitive.sql`。V3 修改列排序规则，沿用既有唯一索引。完成后启动新版并通过注册页重建测试账号。

```sql
ALTER TABLE users
    MODIFY COLUMN username VARCHAR(32)
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
```
