package com.liveflow.infrastructure.message;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** 以只读消费者抽样实际待处理事件年龄。该消费者不加入分组，也不提交位点。 */
final class RoomAdmissionProbe implements AutoCloseable {
    private static final Duration QUERY_TIMEOUT = Duration.ofMillis(650);
    private final KafkaConsumer<String, String> consumer;
    private final RoomEventCodec codec;

    /** @param base Kafka 连接配置
     * @param group 正式接收的实际消费组
     * @param codec 提交事件编解码
     */
    RoomAdmissionProbe(Map<String, Object> base, String group, RoomEventCodec codec) {
        var config = RoomKafkaConsumer.config(base, group);
        // 探针只读且对待测分区显式 seek。其他暂停分区允许初始化空位置。
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(config,
                new StringDeserializer(), new StringDeserializer());
        this.codec = codec;
    }

    /** @param assigned 本机已经恢复的任务分区
     * @return 这些分区的明确空闲、最老记录时间或未知状态
     */
    Map<Integer, RoomAdmissionStatus.Lag> observe(Set<TopicPartition> assigned) {
        if (assigned.isEmpty()) return Map.of();
        if (!consumer.assignment().equals(assigned)) consumer.assign(assigned);
        var committed = consumer.committed(assigned, QUERY_TIMEOUT);
        var starts = consumer.beginningOffsets(assigned, QUERY_TIMEOUT);
        var ends = consumer.endOffsets(assigned, QUERY_TIMEOUT);
        Map<Integer, RoomAdmissionStatus.Lag> observations = new HashMap<>();
        Map<TopicPartition, Long> pending = new HashMap<>();
        for (TopicPartition partition : assigned) {
            var offset = committed.get(partition);
            long start = starts.get(partition);
            long end = ends.get(partition);
            long next = offset == null ? start : offset.offset();
            if (next < start || next > end) {
                observations.put(partition.partition(), new RoomAdmissionStatus.Lag(false, null));
            } else if (next == end) {
                observations.put(partition.partition(), new RoomAdmissionStatus.Lag(true, null));
            } else {
                pending.put(partition, next);
            }
        }
        if (pending.isEmpty()) return observations;
        consumer.pause(assigned);
        consumer.resume(pending.keySet());
        pending.forEach(consumer::seek);
        var batch = consumer.poll(Duration.ofMillis(250));
        for (var partition : pending.keySet()) {
            ConsumerRecord<String, String> first = batch.records(partition).stream().findFirst().orElse(null);
            if (first != null) {
                // 以业务许可时间计龄，提交延迟也计入 12 分钟的处理预算。
                try {
                    var submission = codec.submission(first.key(), first.value());
                    long oldest = java.time.Instant.parse(submission.admittedAt()).toEpochMilli();
                    observations.put(partition.partition(), new RoomAdmissionStatus.Lag(true, oldest));
                } catch (RuntimeException invalid) {
                    // 正式消费者负责落故障记录，本探针只收紧此分区的准入。
                    observations.put(partition.partition(), new RoomAdmissionStatus.Lag(false, null));
                }
            } else {
                boolean caughtUp = consumer.position(partition, QUERY_TIMEOUT) >= ends.get(partition);
                observations.put(partition.partition(), new RoomAdmissionStatus.Lag(caughtUp, null));
            }
        }
        return observations;
    }

    /** 停止只读采样并释放连接。 */
    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(1));
    }
}
