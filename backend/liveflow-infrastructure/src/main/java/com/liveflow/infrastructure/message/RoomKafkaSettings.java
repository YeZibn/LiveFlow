package com.liveflow.infrastructure.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/** 生产者、Streams 和消费者共同使用的连接配置及部署参数。 */
@Component
final class RoomKafkaSettings {
    final Map<String, Object> base;
    final Map<String, Object> consumerBase;
    final short replicas;
    final int minIsr;
    final String stateDirectory;
    final boolean accepting;
    final boolean enabled;

    /**
     * @param kafka Kafka 连接配置入口
     * @param replicas 业务及 Streams 内部主题副本数
     * @param minIsr 最少同步副本数
     * @param stateDirectory 当前实例独占的 Streams 状态目录
     * @param accepting 是否开放生产，停写时后台可继续处理
     * @param enabled 是否启动弹幕 Kafka 链路
     * @param sessionMillis 消费组会话超时
     * @param heartbeatMillis 消费组心跳间隔
     */
    RoomKafkaSettings(KafkaAdmin kafka,
            @Value("${liveflow.room-messages.v2.replication-factor:1}") short replicas,
            @Value("${liveflow.room-messages.v2.min-insync-replicas:1}") int minIsr,
            @Value("${liveflow.room-messages.v2.state-directory:/tmp/liveflow-room-streams-v2}") String stateDirectory,
            @Value("${liveflow.room-messages.v2.accepting:true}") boolean accepting,
            @Value("${liveflow.room-messages.v2.enabled:true}") boolean enabled,
            @Value("${liveflow.room-messages.v2.group-session-millis:10000}") int sessionMillis,
            @Value("${liveflow.room-messages.v2.group-heartbeat-millis:2000}") int heartbeatMillis) {
        if (replicas < 1 || minIsr < 1 || minIsr > replicas
                || stateDirectory.isBlank() || heartbeatMillis <= 0 || sessionMillis / 3 < heartbeatMillis) {
            throw new IllegalArgumentException("弹幕 Kafka 配置无效");
        }
        base = Collections.unmodifiableMap(new HashMap<>(kafka.getConfigurationProperties()));
        var consumer = new HashMap<>(base);
        consumer.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionMillis);
        consumer.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatMillis);
        consumerBase = Collections.unmodifiableMap(consumer);
        this.replicas = replicas;
        this.minIsr = minIsr;
        this.stateDirectory = stateDirectory;
        this.accepting = accepting;
        this.enabled = enabled;
    }
}
