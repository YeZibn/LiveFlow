ALTER TABLE rooms
    ADD COLUMN room_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_rooms_room_uuid (room_uuid);
