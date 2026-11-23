-- 浏览与搜索共用创建时间和房间 UUID 的倒序边界。
ALTER TABLE rooms
    ADD KEY idx_rooms_created_at_room_uuid (created_at DESC, room_uuid DESC);
