-- Keep existing history and its highest persisted sequence.
ALTER TABLE room_message_history_state
    DROP CHECK ck_room_message_history_range,
    CHANGE COLUMN stored_through latest_stored_seq BIGINT NOT NULL DEFAULT 0,
    DROP COLUMN earliest_seq,
    ADD CONSTRAINT ck_room_message_history_max
        CHECK (latest_stored_seq BETWEEN 0 AND 9223372036854775806);

-- Expiration now starts at formal acceptance, regardless of storage delay.
ALTER TABLE room_messages
    DROP INDEX idx_room_messages_stored_at,
    ADD INDEX idx_room_messages_sent_at (sent_at, room_id, room_seq);
