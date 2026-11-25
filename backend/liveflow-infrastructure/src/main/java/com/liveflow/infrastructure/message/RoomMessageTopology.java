package com.liveflow.infrastructure.message;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.utils.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 持有与既有 Kafka 数据兼容的主题、消费组和房间分区约定。 */
@Component
final class RoomMessageTopology {
    static final int PARTITIONS = 6;
    private final String prefix;
    private final String namespace;

    /** @param namespace 当前环境共享的 Kafka 命名空间，留空使用正式名称 */
    RoomMessageTopology(@Value("${liveflow.room-messages.v2.kafka-namespace:}") String namespace) {
        if (!namespace.matches("[A-Za-z0-9_-]{0,32}")) throw new IllegalArgumentException("弹幕 Kafka 命名无效");
        this.namespace = namespace.isEmpty() ? "main" : namespace;
        this.prefix = namespace.isEmpty() ? "" : namespace + ".";
    }

    /** @param kind 事件类型
     * @return 包含环境前缀的 Kafka 主题
     */
    String topic(String kind) {
        return prefix + "liveflow.room-message." + kind + ".v1";
    }

    /** @param role 消费职责
     * @return 跨实例共享的消费组或 Streams 应用标识
     */
    String group(String role) {
        return prefix + "liveflow-room-message-" + role + "-v1";
    }

    /** @return Redis 运行状态使用的 Kafka 命名空间 */
    String namespace() {
        return namespace;
    }

    /** @param roomId 规范房间 UUID
     * @return 与既有 Kafka 默认键分区算法一致的分区编号
     */
    static int partition(String roomId) {
        return Utils.toPositive(Utils.murmur2(roomId.getBytes(StandardCharsets.UTF_8))) % PARTITIONS;
    }
}
