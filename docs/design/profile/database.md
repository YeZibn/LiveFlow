# 个人资料数据库设计

## 数据归属与关系

MySQL 的 `users` 保存账号 ID、登录用户名和密码哈希。新增 `user_profiles` 保存用户编辑的展示资料。每个账号对应零或一条资料记录，资料通过 `user_id` 关联账号。没有资料记录时，服务端以账号数据和接口默认值组装响应。 [模块设计](design.md) · [账号表设计](../auth/database.md) · [默认资料](api.md#读取资料)

## 表字段与索引

| 字段 | 类型 | 可空及默认 | 语义 |
| --- | --- | --- | --- |
| `user_id` | `BIGINT` | 必填 | 有符号账号 ID，与 `users.id` 类型一致。资料表主键及外键。 |
| `nickname` | `VARCHAR(24)` | 必填，默认 `''` | 规范化昵称，空值回退到登录用户名。 |
| `avatar_key` | `VARCHAR(16)` | 必填，默认 `default` | 预设头像标识，ASCII 且区分大小写。合法值由 [接口契约](api.md#更新资料) 定义。 |
| `bio` | `VARCHAR(160)` | 必填，默认 `''` | 规范化后的纯文本简介。 |
| `created_at` | `DATETIME(3)` | 必填 | 首次保存资料的 UTC 时间。 |
| `updated_at` | `DATETIME(3)` | 必填 | 最近一次保存写入的 UTC 时间。 |

| 名称 | 类型 | 对应场景 |
| --- | --- | --- |
| `PRIMARY (user_id)` | 主键 | 按账号 ID 等值读取、更新并约束一人一条资料。该索引同时覆盖子表外键列。 |
| `fk_user_profiles_user` | 外键 | 资料归属有效账号。账号记录删除时级联删除资料。 |

首版按账号 ID 访问。昵称允许重名，索引只需上述主键。

## DDL

在 `liveflow-core` 的 `src/main/resources/db/migration/` 新增 `V2__create_user_profiles.sql`，在现有用户表迁移之后执行。 [V1 用户表迁移](../../../backend/liveflow-core/src/main/resources/db/migration/V1__create_users.sql)

```sql
CREATE TABLE user_profiles (
    user_id BIGINT NOT NULL,
    nickname VARCHAR(24) NOT NULL DEFAULT '',
    avatar_key VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'default',
    bio VARCHAR(160) NOT NULL DEFAULT '',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC 首次保存时间',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC 最近保存时间',
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;
```

## 读写与事务

### 读取

Mapper 以可信账号 ID 查询 `users`，左连接资料表。Service 根据关联结果组装默认资料及 `displayName`。账号行缺失按会话失效处理。

```sql
SELECT u.id, u.username, p.nickname, p.avatar_key, p.bio
FROM users AS u
LEFT JOIN user_profiles AS p ON p.user_id = u.id
WHERE u.id = #{userId};
```

### 保存

Service 完成整份表单的规范化和校验后开启写入事务。Mapper 使用绑定参数和单条 MySQL upsert，已存在主键时更新三个编辑字段及更新时间，首次保存时间沿用原值。随后在同一事务内按账号回读，再提交事务。首次保存的时间字段使用 UTC 毫秒精度。 [MySQL 8.4 upsert 语义](https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html)

```sql
INSERT INTO user_profiles (user_id, nickname, avatar_key, bio, created_at, updated_at)
SELECT id, #{nickname}, #{avatarKey}, #{bio}, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM users WHERE id = #{userId}
ON DUPLICATE KEY UPDATE
    nickname = #{nickname},
    avatar_key = #{avatarKey},
    bio = #{bio},
    updated_at = UTC_TIMESTAMP(3);
```

同一账号的两个首次保存请求由主键和数据库写入顺序裁决。最终只有一行，后提交的整份资料生效。明确的写入或提交失败按事务结果处理，保存响应丢失时通过重新查询确认当前数据。写入从仍存在的账号取主键，外键保护账号归属。回读发现账号不存在时按会话失效处理。

## 迁移与兼容

V2 创建空资料表，现有账号首次查看时可立即读取默认资料，首次保存时创建对应记录。发布时先让 Flyway 完成 V2，再开放资料接口。应用版本回退时保留资料表及用户已经保存的数据，后续重新发布后可继续读取。
