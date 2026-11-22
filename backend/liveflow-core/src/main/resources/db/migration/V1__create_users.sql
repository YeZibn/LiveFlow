CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL COMMENT 'UTC 创建时间',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC 更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB;
