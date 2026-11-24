CREATE TABLE room_message_history_state (
    room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stored_through BIGINT NOT NULL,
    earliest_seq BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (room_id),
    CONSTRAINT ck_room_message_history_range
        CHECK (stored_through >= 0 AND earliest_seq > 0 AND earliest_seq - 1 <= stored_through)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_messages (
    room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    room_seq BIGINT NOT NULL,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sender_user_id BIGINT NOT NULL,
    sender_name VARCHAR(32) NOT NULL,
    content VARCHAR(100) NOT NULL,
    sent_at DATETIME(3) NOT NULL,
    stored_at DATETIME(3) NOT NULL,
    PRIMARY KEY (room_id, room_seq),
    UNIQUE KEY uk_room_messages_message_id (message_id),
    KEY idx_room_messages_stored_at (stored_at, room_id, room_seq),
    CONSTRAINT ck_room_messages_sequence CHECK (room_seq BETWEEN 1 AND 9223372036854775806)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
