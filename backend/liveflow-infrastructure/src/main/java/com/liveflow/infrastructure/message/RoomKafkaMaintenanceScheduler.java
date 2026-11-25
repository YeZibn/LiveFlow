package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomHistoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.infrastructure.message.RoomMessageTopology.PARTITIONS;

/** 调度独立的回执重建和历史清理，多个实例通过各自租约协调。 */
@Component
public final class RoomKafkaMaintenanceScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(RoomKafkaMaintenanceScheduler.class);
    private final RoomKafkaSettings settings;
    private final RoomMessageTopology topology;
    private final RoomMessageInstance instance;
    private final RoomMaintenanceLease maintenance;
    private final RoomHistoryService history;
    private final RedisMessageState state;
    private final StringRedisTemplate redis;
    private final RoomEventCodec codec;
    private volatile Object activeRun;

    RoomKafkaMaintenanceScheduler(RoomKafkaSettings settings, RoomMessageTopology topology,
            RoomMessageInstance instance, RoomMaintenanceLease maintenance, RoomHistoryService history,
            RedisMessageState state, StringRedisTemplate redis, JsonMapper json) {
        this.settings = settings;
        this.topology = topology;
        this.instance = instance;
        this.maintenance = maintenance;
        this.history = history;
        this.state = state;
        this.redis = redis;
        codec = new RoomEventCodec(json);
    }

    void start() {
        activeRun = new Object();
    }

    /** 在途任务按批次看到状态变化，由任务线程关闭临时客户端。 */
    void stop() {
        activeRun = null;
    }

    /**
     * 在 Redis 回执命名空间缺少恢复标记时，独立重放查询期内的事件。
     * 正常投影组保留自己的消费位置，过期事件由 Redis 脚本跳过。
     */
    @Scheduled(fixedDelayString = "${liveflow.room-messages.v2.receipt-recovery-millis:30000}")
    public void restoreReceipts() {
        Object currentRun = activeRun;
        if (currentRun == null) return;
        String marker = state.receiptProjectionMarker(topology.namespace());
        try {
            if (Boolean.TRUE.equals(redis.expire(marker, Duration.ofMinutes(2)))) return;
            var acquired = maintenance.acquire("receipt-rebuild");
            if (acquired.isEmpty()) return;
            try (var lease = acquired.get()) {
                if (Boolean.TRUE.equals(redis.expire(marker, Duration.ofMinutes(2)))) return;
                restoreReceiptWindow(lease, currentRun);
                if (activeRun != currentRun) return;
                if (!lease.complete(marker, Duration.ofMinutes(2))) {
                    throw new IllegalStateException("回执恢复租约已失效");
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("弹幕回执恢复将在下一轮重试 failure={}", failure.getClass().getSimpleName());
        }
    }

    /** 按共享租约清理过期历史，各实例独立通知本机连接的历史进度。 */
    @Scheduled(fixedDelayString = "${liveflow.room-messages.v2.history-cleanup-millis:30000}")
    public void cleanHistory() {
        Object currentRun = activeRun;
        if (currentRun == null) return;
        try {
            var acquired = maintenance.acquire("history-cleanup");
            if (acquired.isEmpty()) return;
            try (var lease = acquired.get()) {
                history.clean(() -> activeRun == currentRun && lease.renew());
            }
        } catch (RuntimeException failure) {
            LOG.warn("弹幕历史清理将在下一轮恢复 instance={} failure={}", instance.id(), failure.getClass().getSimpleName());
        }
    }

    /**
     * @param lease 当前回执重建租约
     * @param currentRun 本轮调度所属的生命周期，用于在批次切换时识别停机
     */
    private void restoreReceiptWindow(RoomMaintenanceLease.Lease lease, Object currentRun) {
        var props = RoomKafkaConsumer.config(settings.consumerBase, topology.group("receipt-rebuild"));
        String topic = topology.topic("receipts");
        try (var replay = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            var partitions = IntStream.range(0, PARTITIONS)
                    .mapToObj(index -> new TopicPartition(topic, index)).toList();
            replay.assign(partitions);
            var ends = replay.endOffsets(partitions, Duration.ofSeconds(3));
            long since = Instant.now().minusSeconds(1801).toEpochMilli();
            Map<TopicPartition, Long> timestamps = new HashMap<>();
            partitions.forEach(partition -> timestamps.put(partition, since));
            replay.offsetsForTimes(timestamps, Duration.ofSeconds(3)).forEach((partition, offset) ->
                    replay.seek(partition, offset == null ? ends.get(partition) : Math.min(offset.offset(), ends.get(partition))));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (partitions.stream().anyMatch(partition -> replay.position(partition) < ends.get(partition))) {
                if (activeRun != currentRun || System.nanoTime() >= deadline || !lease.renew()) throw new IllegalStateException("回执回放未完成");
                var records = replay.poll(Duration.ofMillis(200));
                if (activeRun != currentRun) return;
                for (var record : records) {
                    if (record.offset() < ends.get(new TopicPartition(topic, record.partition()))) {
                        state.project(codec.receipt(record.key(), record.value()));
                    }
                }
            }
        }
    }

}
