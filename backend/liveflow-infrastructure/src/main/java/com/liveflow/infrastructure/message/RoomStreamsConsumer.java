package com.liveflow.infrastructure.message;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/** 在 Streams 主消费者入口保留受阻的分区，允许同一进程处理健康任务。 */
final class RoomStreamsConsumer extends KafkaConsumer<byte[], byte[]> {
    private static final Logger LOG = LoggerFactory.getLogger(RoomStreamsConsumer.class);
    private final RoomProcessingBlocks blocks;
    private final String group;
    private final String topic;
    private final Set<TopicPartition> streamsPaused = new HashSet<>();
    private final Set<TopicPartition> fenced = new HashSet<>();
    private final Set<TopicPartition> resetPosition = new HashSet<>();
    private Set<TopicPartition> lastAssignment = Set.of();
    private long nextCheck;

    /** @param config Streams 为主消费者生成的配置
     * @param blocks 持久阻断存储
     * @param topology 正式接收职责和提交主题
     */
    RoomStreamsConsumer(Map<String, Object> config, RoomProcessingBlocks blocks, RoomMessageTopology topology) {
        super(explicitOffsets(config), new ByteArrayDeserializer(), new ByteArrayDeserializer());
        this.blocks = blocks;
        group = topology.group("admission");
        topic = topology.topic("submissions");
    }

    private static Map<String, Object> explicitOffsets(Map<String, Object> config) {
        var result = new HashMap<>(config);
        result.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        return result;
    }

    /** @param timeout 本次轮询上限
     * @return 可交给 Streams 处理的批次
     */
    @Override
    public ConsumerRecords<byte[], byte[]> poll(Duration timeout) {
        refreshFences();
        try {
            var records = super.poll(timeout);
            refreshFences();
            // 若本次 poll 内重新分配，仍须拦住新取得的受阻分区及其 nextOffset。
            if (fenced.isEmpty()) return records;
            var retained = new HashMap<TopicPartition, List<ConsumerRecord<byte[], byte[]>>>();
            var offsets = new HashMap<>(records.nextOffsets());
            for (TopicPartition partition : records.partitions()) {
                var batch = records.records(partition);
                if (fenced.contains(partition)) super.seek(partition, batch.getFirst().offset());
                else retained.put(partition, batch);
            }
            fenced.forEach(offsets::remove);
            return new ConsumerRecords<>(retained, offsets);
        } catch (NoOffsetForPartitionException firstRun) {
            // 只有从未提交位置的分区才从日志起点初始化。
            super.seekToBeginning(firstRun.partitions());
            return ConsumerRecords.empty();
        } catch (OffsetOutOfRangeException lost) {
            lost.offsetOutOfRangePartitions().forEach((partition, offset) -> {
                blocks.record(group, topic, partition.partition(), "", "OFFSET_OUT_OF_RANGE", offset);
                fenced.add(partition);
                resetPosition.add(partition);
                super.pause(List.of(partition));
                super.seekToBeginning(List.of(partition));
            });
            return ConsumerRecords.empty();
        }
    }

    /** @param partitions Streams 因内部流控或状态恢复而暂停的任务 */
    @Override
    public void pause(Collection<TopicPartition> partitions) {
        streamsPaused.addAll(partitions);
        super.pause(partitions);
    }

    /** @param partitions Streams 已完成内部等待的任务 */
    @Override
    public void resume(Collection<TopicPartition> partitions) {
        streamsPaused.removeAll(partitions);
        super.resume(partitions.stream().filter(partition -> !fenced.contains(partition)).toList());
    }

    private void refreshFences() {
        var assignment = Set.copyOf(assignment());
        long now = System.nanoTime();
        if (assignment.isEmpty() || (assignment.equals(lastAssignment) && now < nextCheck)) return;
        Set<Integer> blocked;
        try {
            blocked = blocks.partitions(group, topic);
        } catch (DataAccessException unavailable) {
            // 保持组成员和心跳。无法确认阻断时停止交付新批次，下一轮重新核对。
            LOG.warn("正式接收阻断读取失败 group={} failure={}", group, unavailable.getClass().getSimpleName());
            blocked = assignment.stream().map(TopicPartition::partition).collect(java.util.stream.Collectors.toSet());
        }
        streamsPaused.retainAll(assignment);
        fenced.retainAll(assignment);
        resetPosition.retainAll(assignment);
        // 暂停由两方共同决定。仅解除本模块的阻断，不覆盖 Streams 的内部暂停。
        for (var partition : assignment) {
            if (blocked.contains(partition.partition())) {
                fenced.add(partition);
                super.pause(List.of(partition));
            } else if (fenced.remove(partition)) {
                if (resetPosition.remove(partition)) {
                    var position = super.committed(Set.of(partition), Duration.ofSeconds(2)).get(partition);
                    if (position == null) super.seekToBeginning(List.of(partition));
                    else super.seek(partition, position.offset());
                }
                if (!streamsPaused.contains(partition)) super.resume(List.of(partition));
            }
        }
        nextCheck = now + Duration.ofSeconds(1).toNanos();
        lastAssignment = assignment;
    }
}
