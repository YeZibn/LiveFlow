CREATE TABLE room_message_processing_blocks (
    consumer_group VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_name VARCHAR(249) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_no INT NOT NULL,
    room_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
    reason VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_offset BIGINT NULL,
    reported_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    PRIMARY KEY (consumer_group, topic_name, partition_no, room_id)
) ENGINE=InnoDB;
