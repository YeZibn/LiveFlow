package com.liveflow.infrastructure.message;

import java.time.Duration;
import com.liveflow.core.room.message.RoomHistoryService.Conflict;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 每个职责独立轮询。单个分区故障期间继续维持其他分区及消费心跳。 */
final class RoomKafkaConsumer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RoomKafkaConsumer.class);
    private final KafkaConsumer<String, String> consumer;
    private final String group;
    private final String topic;
    private final BatchHandler handler;
    private final RoomProcessingBlocks blocks;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<TopicPartition, Long> resumeAt = new HashMap<>();
    private final Map<TopicPartition, Long> lastCommitted = new HashMap<>();
    private final Set<TopicPartition> fenced = new HashSet<>();
    private final Map<TopicPartition, Fault> unsaved = new HashMap<>();
    private long nextBlockCheck;
    private final Map<Integer, AtomicLong> lag = new HashMap<>();
    private final Map<Integer, AtomicLong> lagAge = new HashMap<>();
    private volatile boolean assigned;
    private final Thread worker;

    /**
     * @param base Kafka 连接配置
     * @param group 固定职责消费组
     * @param role 监控使用的消费职责
     * @param topic 对应的事件主题
     * @param partitions 主题分区数
     * @param metrics 消费进度指标
     * @param blocks 共享处理阻断
     * @param handler 结束时已确认外部副作用的批处理
     */
    RoomKafkaConsumer(Map<String, Object> base, String group, String role, String topic, int partitions,
            MeterRegistry metrics, RoomProcessingBlocks blocks, BatchHandler handler) {
        this.group = group;
        this.topic = topic;
        this.handler = handler;
        this.blocks = blocks;
        for (int index = 0; index < partitions; index++) {
            String partition = Integer.toString(index);
            lag.put(index, metrics.gauge("liveflow.room.messages.kafka.lag",
                    io.micrometer.core.instrument.Tags.of("group", role, "partition", partition), new AtomicLong(-1)));
            var pendingSince = new AtomicLong();
            lagAge.put(index, pendingSince);
            metrics.gauge("liveflow.room.messages.kafka.lag.age",
                    io.micrometer.core.instrument.Tags.of("group", role, "partition", partition), pendingSince,
                    value -> value.get() == 0 ? 0 : Math.max(0, System.currentTimeMillis() - value.get()));
        }
        consumer = new KafkaConsumer<>(config(base, group), new StringDeserializer(), new StringDeserializer());
        worker = Thread.ofPlatform().daemon().name(group).start(this::consume);
    }

    static Map<String, Object> config(Map<String, Object> base, String group) {
        var props = new HashMap<>(base);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        return props;
    }

    boolean ready() {
        return assigned && running.get();
    }

    private void consume() {
        try {
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(java.util.Collection<TopicPartition> partitions) {
                    assigned = false;
                    partitions.forEach(partition -> {
                        resumeAt.remove(partition);
                        lastCommitted.remove(partition);
                        fenced.remove(partition);
                        lag.get(partition.partition()).set(-1);
                        lagAge.get(partition.partition()).set(0);
                    });
                }

                @Override
                public void onPartitionsAssigned(java.util.Collection<TopicPartition> partitions) {
                    // 先暂停新分区。第一次消费前确认共享阻断与 Broker 上的实际位点。
                    consumer.pause(partitions);
                    fenced.addAll(partitions);
                    // Kafka 在 poll 内就会初始化位置。未提交的新分区需要明确的起点。
                    consumer.committed(Set.copyOf(partitions), Duration.ofSeconds(2)).forEach((partition, offset) -> {
                        if (offset == null) consumer.seekToBeginning(List.of(partition));
                        else consumer.seek(partition, offset.offset());
                    });
                    assigned = !partitions.isEmpty() || !consumer.assignment().isEmpty();
                    nextBlockCheck = 0;
                }
            });
            while (running.get()) {
                refreshBlocks();
                var iterator = resumeAt.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getValue() <= System.nanoTime()) {
                        if (!fenced.contains(entry.getKey())) consumer.resume(List.of(entry.getKey()));
                        iterator.remove();
                    }
                }
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(100));
                } catch (OffsetOutOfRangeException failure) {
                    fenceExpiredOffsets(failure);
                    continue;
                }
                var paused = consumer.paused();
                for (var partition : consumer.assignment()) {
                    if (paused.contains(partition)) continue;
                    int fetched = records.records(partition).size();
                    consumer.currentLag(partition).ifPresent(offset -> lag.get(partition.partition()).set(offset + fetched));
                }
                records.nextOffsets().forEach((partition, next) -> {
                    if (!paused.contains(partition)) consumePartition(partition, records.records(partition), next);
                });
            }
        } catch (WakeupException shutdown) {
            if (running.get()) LOG.error("弹幕消费意外中断 group={}", group);
        } catch (RuntimeException failure) {
            LOG.error("弹幕消费退出 group={} failure={}", group, failure.getClass().getSimpleName());
        } finally {
            assigned = false;
            running.set(false);
            try {
                consumer.close(Duration.ofSeconds(3));
            } catch (RuntimeException failure) {
                LOG.warn("弹幕消费组关闭未确认 group={} failure={}", group, failure.getClass().getSimpleName());
            }
        }
    }

    private void consumePartition(TopicPartition partition, List<ConsumerRecord<String, String>> batch,
            OffsetAndMetadata next) {
        if (next.offset() <= lastCommitted.getOrDefault(partition, -1L)) return;
        boolean hasEvents = !batch.isEmpty();
        if (hasEvents) lagAge.get(partition.partition()).set(batch.getFirst().timestamp());
        try {
            if (hasEvents) handler.handle(batch);
            // next 也跨过同一批已读取的 Kafka 事务标记。外部副作用确认之后才持久化消费位置。
            consumer.commitSync(Map.of(partition, next), Duration.ofSeconds(2));
            lastCommitted.put(partition, next.offset());
            lagAge.get(partition.partition()).set(0);
        } catch (WakeupException shutdown) {
            throw shutdown;
        } catch (Conflict failure) {
            pause(partition);
        } catch (InvalidEvent failure) {
            recordBlock(partition, new Fault("PROCESSING_ERROR", failure.offset));
        } catch (RuntimeException failure) {
            long first = hasEvents ? batch.getFirst().offset() : lastCommitted.getOrDefault(partition, next.offset());
            LOG.warn("弹幕消费暂停 group={} partition={} offset={} failure={}",
                    group, partition, first, failure.getClass().getSimpleName());
            consumer.seek(partition, first);
            consumer.pause(List.of(partition));
            resumeAt.put(partition, System.nanoTime() + Duration.ofSeconds(2).toNanos());
        }
    }

    private void fenceExpiredOffsets(OffsetOutOfRangeException failure) {
        failure.offsetOutOfRangePartitions().forEach((partition, offset) -> {
            recordBlock(partition, new Fault("OFFSET_OUT_OF_RANGE", offset));
            // 移开客户端内存中的非法位置。解除后重新读取 Broker 位点，期间不提交替代位置。
            consumer.seekToBeginning(List.of(partition));
        });
    }

    private void refreshBlocks() {
        long now = System.nanoTime();
        if (now < nextBlockCheck || consumer.assignment().isEmpty() && unsaved.isEmpty()) return;
        nextBlockCheck = now + Duration.ofSeconds(1).toNanos();
        try {
            var pending = unsaved.entrySet().iterator();
            while (pending.hasNext()) {
                var entry = pending.next();
                storeBlock(entry.getKey(), entry.getValue());
                pending.remove();
            }
            Set<Integer> blocked = blocks.partitions(group, topic);
            var toResume = new HashSet<TopicPartition>();
            for (var partition : consumer.assignment()) {
                if (blocked.contains(partition.partition())) pause(partition);
                else if (fenced.contains(partition)) toResume.add(partition);
            }
            if (toResume.isEmpty()) return;
            var positions = consumer.committed(toResume, Duration.ofSeconds(2));
            for (var partition : toResume) {
                var offset = positions.get(partition);
                if (offset == null) {
                    lastCommitted.remove(partition);
                    consumer.seekToBeginning(List.of(partition));
                } else {
                    lastCommitted.put(partition, offset.offset());
                    consumer.seek(partition, offset.offset());
                }
            }
            fenced.removeAll(toResume);
            consumer.resume(toResume);
        } catch (WakeupException shutdown) {
            throw shutdown;
        } catch (RuntimeException failure) {
            LOG.warn("弹幕分区阻断检查失败 group={} failure={}", group, failure.getClass().getSimpleName());
        }
    }

    private void pause(TopicPartition partition) {
        consumer.pause(List.of(partition));
        fenced.add(partition);
        resumeAt.remove(partition);
        lag.get(partition.partition()).set(-1);
    }

    private void recordBlock(TopicPartition partition, Fault fault) {
        pause(partition);
        LOG.error("弹幕分区阻断 group={} partition={} reason={} offset={}",
                group, partition, fault.reason, fault.offset);
        try {
            storeBlock(partition, fault);
        } catch (RuntimeException failure) {
            unsaved.put(partition, fault);
            LOG.error("弹幕分区阻断保存失败 group={} partition={}", group, partition, failure);
        }
    }

    private void storeBlock(TopicPartition partition, Fault fault) {
        blocks.record(group, topic, partition.partition(), "", fault.reason, fault.offset);
    }

    private record Fault(String reason, long offset) {}

    /** 确定无法解析的持久事件。数据库或 Redis 的暂时故障沿用普通退避。 */
    static final class InvalidEvent extends IllegalStateException {
        private final long offset;

        /** @param offset 无法处理的事件位点
         * @param cause 原始事件契约错误
         */
        InvalidEvent(long offset, Throwable cause) {
            super("弹幕事件契约无效", cause);
            this.offset = offset;
        }
    }

    @Override
    public void close() {
        if (running.getAndSet(false)) consumer.wakeup();
        try {
            worker.join(Duration.ofSeconds(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface BatchHandler {
        void handle(List<ConsumerRecord<String, String>> records);
    }
}
