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

    /**
     * 本机分发事件。同一事件为该房间的接收者提供一致的显示字段。
     * @param roomId 仅用于路由到对应的房间
     * @param messageId 与 HTTP 回执共用的消息 UUID
     * @param senderName 受理该条消息时读取的发送者显示名
     * @param content 已规范化的纯文本正文
     * @param sentAt 服务端受理时间
     */
    record Message(String roomId, String messageId, String senderName, String content, Instant sentAt) {}
}
