package com.liveflow.infrastructure.message;

import java.time.Duration;
import java.util.HashMap;
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
    private final int partitionCount;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<TopicPartition, Long> resumeAt = new HashMap<>();
    private final Map<TopicPartition, Long> lastCommitted = new HashMap<>();
    private final java.util.Set<Integer> invalidOffsets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<Integer, AtomicLong> lag = new HashMap<>();
    private final Map<Integer, AtomicLong> lagAge = new HashMap<>();
    private volatile boolean assigned;
    private final Thread worker;

    /**
     * @param base Kafka 连接配置
     * @param group 固定职责消费组
     * @param topic 对应的正式事件主题
     * @param handler 结束时已确认外部副作用的批处理
     */
    RoomKafkaConsumer(Map<String, Object> base, String group, String role, String topic, int partitions,
            MeterRegistry metrics, BatchHandler handler) {
        this.group = group;
        this.topic = topic;
        this.handler = handler;
        this.partitionCount = partitions;
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

    boolean retains(int partition) {
        return ready() && !invalidOffsets.contains(partition);
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
                    });
                }

                @Override
                public void onPartitionsAssigned(java.util.Collection<TopicPartition> partitions) {
                    var set = java.util.Set.copyOf(partitions);
                    // 缺失的首次提交位置从保留起点开始。已有位置意外越界由 none 阻止跳读。
                    consumer.committed(set, Duration.ofSeconds(3)).forEach((partition, offset) -> {
                        if (offset == null) consumer.seekToBeginning(List.of(partition));
                        else lastCommitted.put(partition, offset.offset());
                    });
                    consumer.pause(set.stream().filter(partition -> invalidOffsets.contains(partition.partition())).toList());
                    assigned = partitions.size() == partitionCount;
                }
            });
            while (running.get()) {
                var iterator = resumeAt.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getValue() <= System.nanoTime()) {
                        consumer.resume(List.of(entry.getKey()));
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
            LOG.error("弹幕消费位置超出保留范围，等待数据修复 group={} partition={} offset={}", group, partition, offset);
            invalidOffsets.add(partition.partition());
            resumeAt.remove(partition);
            // 只将客户端内存中的位置移出非法范围。暂停该分区且从不提交这次 seek。
            consumer.pause(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
        });
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
