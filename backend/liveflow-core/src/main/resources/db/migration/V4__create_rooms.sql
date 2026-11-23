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
