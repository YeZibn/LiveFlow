# 直播间数据库设计

## 数据归属与访问场景

MySQL 的 `rooms` 保存房间标识、房主、名称和创建时间。每条房间记录归属 `users` 中的一个账号，每个账号有零或一条记录。房间 Service 在创建时写入，提供本人房间、详情和统一列表三种读取方式。列表和详情关联用户资料，返回房主当前的显示名。 [业务流程](design.md#端到端流程) · [个人资料数据库设计](../profile/database.md)

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
| `idx_rooms_created_at_room_uuid` | `created_at DESC, room_uuid DESC` | 支持列表浏览顺序与游标查询的排序键。 |
| `fk_rooms_owner_user` | `owner_user_id` | 外键，引用 `users(id)`，有房间的账号禁止直接删除。 |

房间名称允许重复。列表先沿组合索引取页，避免对全部候选房间排序。包含搜索在游标范围内逐行匹配标题。

## DDL

UUID 的既有迁移由 V5 增加可空列与唯一索引，V6 回填，V7 固定非空约束。 [V5 增列](../../../backend/liveflow-core/src/main/resources/db/migration/V5__add_room_uuid.sql) · [V6 回填](../../../backend/liveflow-core/src/main/java/db/migration/V6__backfill_room_uuid.java) · [V7 约束](../../../backend/liveflow-core/src/main/resources/db/migration/V7__require_room_uuid.sql)

列表索引通过 V7 之后的独立 Flyway 迁移添加。既有房间直接纳入新索引。

```sql
ALTER TABLE rooms
    ADD KEY idx_rooms_created_at_room_uuid (created_at DESC, room_uuid DESC);
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

详情按 UUID 查询。列表和详情共用房主显示名规则。有昵称时保留昵称，资料行缺失或昵称为空时回退到账号名。对外响应只包含页面需要的字段。 [个人资料接口](../profile/api.md#成功响应)

```sql
SELECT r.room_uuid, r.title,
       CASE WHEN p.nickname IS NULL OR CHAR_LENGTH(p.nickname) = 0
            THEN u.username ELSE p.nickname END AS owner_name
FROM rooms AS r
JOIN users AS u ON u.id = r.owner_user_id
LEFT JOIN user_profiles AS p ON p.user_id = u.id
WHERE r.room_uuid = #{roomUuid};
```

### 列表查询与分页

一次查询取得房间、房主显示名和 `isMine`。先从房间表确定当页记录，再关联房主资料，让资料查询只覆盖当页。外层明确重申排序。通过主体账号 ID 与房主 ID 比较计算 `isMine`。规范化后的 `q` 为空时省略 `LIKE` 条件。首屏省略游标条件。Mapper 用绑定参数传入搜索模式、游标边界和读取条数。

```sql
SELECT r.room_uuid, r.title, r.created_at,
       CASE WHEN p.nickname IS NULL OR CHAR_LENGTH(p.nickname) = 0
            THEN u.username ELSE p.nickname END AS owner_name,
       (r.owner_user_id = #{viewerUserId}) AS is_mine
FROM (
    SELECT room_uuid, title, created_at, owner_user_id
    FROM rooms FORCE INDEX (idx_rooms_created_at_room_uuid)
    WHERE title COLLATE utf8mb4_0900_as_ci LIKE #{titlePattern} ESCAPE '!'
      AND (created_at < #{cursorCreatedAt}
           OR (created_at = #{cursorCreatedAt} AND room_uuid < #{cursorRoomUuid}))
    ORDER BY created_at DESC, room_uuid DESC
    LIMIT #{fetchSize}
) AS r
JOIN users AS u ON u.id = r.owner_user_id
LEFT JOIN user_profiles AS p ON p.user_id = u.id
ORDER BY r.created_at DESC, r.room_uuid DESC;
```

1. 使用 `utf8mb4_0900_as_ci` 比较标题和关键词，忽略英文大小写，区分重音。构造搜索模式时先转义原始的 `!`，再转义 `%` 和 `_`，最后在两端添加 `%`。例如关键词 `50%_!` 的绑定值为 `%50!%!_!!%`。
2. 在 MySQL 中按 UTC `DATETIME(3)` 和 `ascii_bin` 的 UUID 比较位置。读取 `limit + 1` 条。多读到一条时返回前 `limit` 条，并以最后一条已返回记录的排序键生成 `nextCursor`。否则返回全部读到的记录和空游标。
3. 游标采用无填充 Base64URL 编码，内容包含版本号、UTC 毫秒时间戳、标准小写房间 UUID，以及规范化关键词 UTF-8 字节的 SHA-256 摘要。空搜索使用空字符串的摘要。解码时校验格式、版本、时间范围、UUID 和关键词摘要。任何无效值映射为接口参数错误。游标仅提供读取边界，请求仍须通过正常鉴权。
4. 每页读取当次可见的数据。浏览途中在游标之前新建的房间在重新加载首页时出现。游标保存排序键，房间边界记录是否仍存在不影响后续页读取。

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

已部署环境由 Flyway 在 V7 之后创建列表索引，随后提供列表接口。全新环境按已有迁移顺序建表、回填 UUID 并加索引。回退到已有 UUID 版时保留列表索引和房间数据。
