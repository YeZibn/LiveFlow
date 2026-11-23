# 直播间数据库设计

## 数据归属与访问场景

MySQL 的 `rooms` 保存房间标识、房主、名称和创建时间。每条房间记录归属 `users` 中的一个账号，每个账号有零或一条记录。房间 Service 只在用户主动创建时写入，查询按当前账号 ID 定位。 [业务流程](design.md#端到端流程) · [账号表设计](../auth/database.md)

## 表字段与索引

| 字段 | 类型 | 可空及默认 | 含义 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 必填，自增 | 有符号主键，稳定标识房间。 |
| `owner_user_id` | `BIGINT` | 必填，无默认 | 有符号账号 ID，与 `users.id` 同型。 |
| `title` | `VARCHAR(40)` | 必填，无默认 | 使用 `utf8mb4` 保存规范化后的房间名称。 [名称规则](api.md#创建房间) |
| `created_at` | `DATETIME(3)` | 必填，无默认 | 数据库在插入时写入的 UTC 创建时间，毫秒精度。 |

| 名称 | 列 | 作用 |
| --- | --- | --- |
| `PRIMARY` | `id` | 主键，保证房间标识唯一。 |
| `uk_rooms_owner_user_id` | `owner_user_id` | 唯一索引，保证一人一房，并覆盖按房主等值查询及外键子列。 |
| `fk_rooms_owner_user` | `owner_user_id` | 外键，引用 `users(id)`，有房间的账号禁止直接删除。 |

房间名称允许重复。首版按房主读取，无按名称或时间的查询路径，因此只需上述两个索引。

## DDL

在 `liveflow-core/src/main/resources/db/migration/` 新增 `V4__create_rooms.sql`，接在现有账号迁移之后。 [数据库迁移目录](../../../backend/liveflow-core/src/main/resources/db/migration)

```sql
CREATE TABLE rooms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    title VARCHAR(40) NOT NULL,
    created_at DATETIME(3) NOT NULL COMMENT 'UTC 创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rooms_owner_user_id (owner_user_id),
    CONSTRAINT fk_rooms_owner_user
        FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;
```

## 读写与事务

### 读取

按可信账号 ID 走等值查询。账号存在且 `room_id` 为空时返回空房间结果。账号不存在时按会话失效处理。房间读取使用权威 MySQL 数据，创建后的确认查询同样走这条路径。

```sql
SELECT u.id AS owner_user_id, r.id AS room_id, r.title, r.created_at
FROM users AS u
LEFT JOIN rooms AS r ON r.owner_user_id = u.id
WHERE u.id = #{ownerUserId};
```

### 创建

Service 先完成名称规范化与校验，再在写入事务内执行单次插入并回读本次写入的房间。Mapper 使用绑定参数。账号不存在时插入影响零行，按会话失效处理。

```sql
INSERT INTO rooms (owner_user_id, title, created_at)
SELECT id, #{title}, UTC_TIMESTAMP(3)
FROM users
WHERE id = #{ownerUserId};
```

- 插入路径以房主唯一索引为准。两个同账号请求并发创建时，若一个事务成功提交，另一个请求命中房主唯一约束并返回 `409 ROOM_ALREADY_EXISTS`。冲突请求无需重写房间或再次插入。
- 成功插入后在同一事务内按房主回读，取得生成的房间主键及创建时间。事务提交后才返回成功响应。
- 回读时按 UTC 解释 `created_at`，按接口约定序列化为带毫秒的 UTC 时间。 [房间对象](api.md#房间对象)
- 明确的存储、回读或提交失败按事务结果处理。只有房主唯一约束冲突映射为已拥有房间，其他存储或事务故障按服务不可用处理。提交结果不明确时用查询确认当前持久化结果。

## 迁移与生命周期

迁移创建空房间表，现有账号无需回填。发布时先完成 Flyway 迁移，再开放房间接口及前端入口。应用回退时保留房间表和已创建记录，以便恢复版本后继续读取。后续引入账号或房间删除能力时，再明确房间归属和数据清理规则。
