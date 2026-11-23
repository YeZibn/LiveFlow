-- V6 已为现有房间生成 UUID。新版本的创建入口负责为后续记录赋值。
ALTER TABLE rooms
    MODIFY COLUMN room_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
