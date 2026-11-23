package com.liveflow.core.room.message;

import java.time.Instant;

/** 将已通过业务裁决的实时消息交给房间接收连接。 */
public interface RoomMessageBroadcast {

    /**
     * 在本机按调用顺序入队。实现应隔离单连接失败，禁止在此方法中执行网络写入。
     * @param message 准备广播的房间弹幕
     * @throws RoomMessageFailure 尚未开始分发且服务无法受理
     */
    void enqueue(Message message);

    /** 本机分发使用的数据，房间 UUID 用于路由。 */
    record Message(String roomId, String messageId, String senderName, String content, Instant sentAt) {}
}
