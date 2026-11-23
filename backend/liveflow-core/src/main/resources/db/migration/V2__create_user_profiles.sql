CREATE TABLE user_profiles (
    user_id BIGINT NOT NULL,
    nickname VARCHAR(24) NOT NULL DEFAULT '',
    avatar_key VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'default',
    bio VARCHAR(160) NOT NULL DEFAULT '',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC 首次保存时间',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC 最近保存时间',
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4;
