package com.liveflow.core.room.message;

import java.util.function.Consumer;

/** 房间实时事件订阅。实时传输丢失的记录由历史接口补齐。 */
public interface RoomMessageBroadcast {
    /**
     * @param roomId 订阅的房间 UUID
     * @param messages 每条公开弹幕的回调，按订阅连接收到的顺序调用
     * @param subscribed Redis 确认订阅或恢复订阅时的回调
     * @param disconnected Redis 订阅连接断开时的回调
     * @return 撤销本次订阅的操作
     */
    Runnable subscribe(String roomId, Consumer<RoomEvents.Message> messages, Runnable subscribed, Runnable disconnected);
}
