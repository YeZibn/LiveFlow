# 认证模块 MySQL 用户表

MySQL `users` 是账号和密码哈希的权威数据源。[登录与受保护请求](design.md#登录与受保护请求)通过 `UserMapper` 读取账号。登录会话、刷新和退出由 [Redis 会话](session.md) 管理。

## 表字段

| 字段 | 类型 | 约束与含义 |
| --- | --- | --- |
| `id` | `BIGINT` | `NOT NULL AUTO_INCREMENT`，主键，Java 使用 `Long`。HTTP JSON 和 JWT 中使用十进制字符串 |
| `username` | `VARCHAR(32)` | `NOT NULL`，字符集 `ascii`，排序规则 `ascii_general_ci`，唯一。应用写入和查询使用规范化后的账号 |
| `password_hash` | `VARCHAR(255)` | `NOT NULL`，保存 Argon2id 编码结果 |
| `created_at` | `DATETIME(3)` | `NOT NULL`，账号创建时间，按 UTC 保存 |
| `updated_at` | `DATETIME(3)` | `NOT NULL`，账号最近更新时间，按 UTC 保存 |

用户名按 [接口字段约定](api.md#请求字段) 转小写并校验后再查询和写入。`ascii_general_ci` 下，`Viewer_01` 和 `viewer_01` 在数据库唯一性检查中视为同一个用户名。 [MySQL ASCII 排序规则](https://dev.mysql.com/doc/refman/8.4/en/charset-we-sets.html) · [OWASP 密码存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)

## 索引与查询

| 索引 | 定义 | 查询与用途 |
| --- | --- | --- |
| `PRIMARY` | `PRIMARY KEY (id)` | InnoDB 聚簇索引，`/me` 按可信用户 ID 等值查询 |
| `uk_users_username` | `UNIQUE KEY uk_users_username (username)` | 登录按规范化用户名等值查询，同时约束用户名唯一 |

以下是计划纳入 Flyway 迁移的建表定义。

```sql
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB;
```

代表性查询使用绑定参数。

```sql
SELECT id, password_hash FROM users WHERE username = ?;
SELECT id, username FROM users WHERE id = ?;
```

登录查询通过用户名二级索引定位记录，再以 `id` 回表读取 `password_hash`。二级索引条目已包含主键 `id`，两个索引覆盖本阶段的账号读路径。 [InnoDB 聚簇索引与二级索引](https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html)

## 读写边界

- 登录和当前用户查询均为账号读取。会话的替换、续期和撤销在 Redis 提交。
- 账号写入时保存规范化后的用户名，唯一性由 `uk_users_username` 裁决。同名和大小写变体触发同一唯一约束。
