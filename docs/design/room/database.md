# 直播间数据库设计

## 数据归属与访问场景

MySQL 的 `rooms` 保存房间标识、房主、名称和创建时间。每条房间记录归属 `users` 中的一个账号，每个账号有零或一条记录。房间 Service 在创建时写入，按账号 ID 查询本人房间，按房间 UUID 查询详情。 [业务流程](design.md#端到端流程) · [账号表设计](../auth/database.md)

## 表字段与索引

| 字段 | 类型 | 可空及默认 | 含义 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 必填，自增 | 有符号内部主键，用于表关联。 |
| `room_uuid` | `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin` | 必填，无默认 | 稳定的标准小写 UUID v4，供接口和前端路由使用。 |
| `owner_user_id` | `BIGINT` | 必填，无默认 | 有符号账号 ID，与 `users.id` 同型。 |
| `title` | `VARCHAR(40)` | 必填，无默认 | 使用 `utf8mb4` 保存规范化后的房间名称。 [名称规则](api.md#创建房间) |
| `created_at` | `DATETIME(3)` | 必填，无默认 | 数据库在插入时写入的 UTC 创建时间，毫秒精度。 |

| 名称 | 列 | 作用 |
| --- | --- | --- |
| `PRIMARY` | `id` | 主键，保证房间标识唯一。 |
| `uk_rooms_room_uuid` | `room_uuid` | 唯一索引，保证对外标识唯一，支持详情等值查询。 |
| `uk_rooms_owner_user_id` | `owner_user_id` | 唯一索引，保证一人一房，并覆盖按房主等值查询及外键子列。 |
| `fk_rooms_owner_user` | `owner_user_id` | 外键，引用 `users(id)`，有房间的账号禁止直接删除。 |

房间名称允许重复。首版的两条查询路径分别使用房主索引和 UUID 索引。

## DDL

现有 `V4__create_rooms.sql` 保持原样。新增迁移先执行下列增列操作，完成回填后再执行必填约束。 [现有房间表迁移](../../../backend/liveflow-core/src/main/resources/db/migration/V4__create_rooms.sql)

```sql
ALTER TABLE rooms
    ADD COLUMN room_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_rooms_room_uuid (room_uuid);

-- 回填完成且没有 NULL 记录后，在下一步迁移执行。
ALTER TABLE rooms
    MODIFY COLUMN room_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
```

## 读写与事务

### 读取

按可信账号 ID 走等值查询。账号存在且 `room_uuid` 为空时返回空房间结果。账号不存在时按会话失效处理。创建后的确认查询使用同一条本人查询路径。

```sql
SELECT u.id AS owner_user_id, r.room_uuid, r.title, r.created_at
FROM users AS u
LEFT JOIN rooms AS r ON r.owner_user_id = u.id
WHERE u.id = #{ownerUserId};
```

详情接口在通过鉴权后按 UUID 查询，只读取页面需要的字段。

```sql
SELECT room_uuid, title
FROM rooms
WHERE room_uuid = #{roomUuid};
```

### 创建

Service 完成名称规范化与校验，由 Java `UUID.randomUUID()` 生成标识，再在写入事务内插入并回读房间。Mapper 使用绑定参数。账号不存在时插入影响零行，按会话失效处理。

```sql
INSERT INTO rooms (room_uuid, owner_user_id, title, created_at)
SELECT #{roomUuid}, id, #{title}, UTC_TIMESTAMP(3)
FROM users
WHERE id = #{ownerUserId};
```

- 插入路径以房主唯一索引为准。两个同账号请求并发创建时，若一个事务成功提交，另一个请求命中房主唯一约束并返回 `409 ROOM_ALREADY_EXISTS`。冲突请求无需重写房间或再次插入。
- 仅命中 `uk_rooms_room_uuid` 时，在失败尝试退出事务后生成新 UUID 并重试一次。再次命中该索引时按存储故障处理。房主唯一冲突仍返回原有 `409`。
- 成功插入后在同一事务内按房主回读已保存的 UUID 及创建时间。事务提交后才返回成功响应。
- 回读时按 UTC 解释 `created_at`，按接口约定序列化为带毫秒的 UTC 时间。 [房间对象](api.md#房间对象)
- 明确的存储、回读或提交失败按事务结果处理。只有房主唯一约束冲突映射为已拥有房间，其他存储或事务故障按服务不可用处理。提交结果不明确时用查询确认当前持久化结果。

## 迁移与生命周期

1. 暂停旧版房间创建流量，再执行新增可空列和唯一索引的迁移。
2. 用 Java 为已有且 `room_uuid IS NULL` 的房间生成随机 UUID v4，按内部主键更新。重复执行时跳过已回填的行，保留房主、名称、创建时间和已分配 UUID。
3. 确认没有空值后设为 `NOT NULL`，再发布使用 UUID 的 API 和 Vue 页面。全新库沿用同一迁移顺序，空表跳过回填。
4. 应用回退时保留 UUID 列、索引及已分配值。旧版写入端在回退期间保持停用，恢复 UUID 版本后再开放创建。
