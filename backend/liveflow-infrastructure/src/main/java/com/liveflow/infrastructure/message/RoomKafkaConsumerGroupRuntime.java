package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomHistoryService;
import com.liveflow.core.room.message.RoomHistoryService.HistoryRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.infrastructure.message.RoomMessageTopology.PARTITIONS;

/** 管理广播、历史与回执三个独立确认的正式消费组。 */
@Component
public final class RoomKafkaConsumerGroupRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(RoomKafkaConsumerGroupRuntime.class);
    private final RoomKafkaSettings settings;
    private final RoomMessageTopology topology;
    private final RoomProcessingBlocks blocks;
    private final RoomHistoryService history;
    private final RedisMessageState state;
    private final StringRedisTemplate redis;
    private final RoomEventCodec codec;
    private final MeterRegistry metrics;
    private final Timer broadcast;
    private final Timer storage;
    private final List<RoomKafkaConsumer> consumers = new ArrayList<>();
    private volatile RoomKafkaConsumer receiptConsumer;

    RoomKafkaConsumerGroupRuntime(RoomKafkaSettings settings, RoomMessageTopology topology,
            RoomProcessingBlocks blocks, RoomHistoryService history, RedisMessageState state,
            StringRedisTemplate redis, JsonMapper json, MeterRegistry metrics) {
        this.settings = settings;
        this.topology = topology;
        this.blocks = blocks;
        this.history = history;
        this.state = state;
        this.redis = redis;
        this.metrics = metrics;
        codec = new RoomEventCodec(json);
        broadcast = RoomKafkaMetrics.stage(metrics, "broadcast");
        storage = RoomKafkaMetrics.stage(metrics, "persisted");
    }

    void start() {
        startConsumer("broadcast", "accepted", this::broadcast);
        startConsumer("history", "accepted", this::persistHistory);
        receiptConsumer = startConsumer("receipt", "receipts", this::projectReceipts);
    }

    private RoomKafkaConsumer startConsumer(String role, String sourceTopic, RoomKafkaConsumer.BatchHandler handler) {
        var worker = new RoomKafkaConsumer(settings.consumerBase, topology.group(role), role,
                topology.topic(sourceTopic), PARTITIONS, metrics, blocks, handler);
        // 每创建一个客户端就登记，后续组件启动失败时也能关闭已打开的消费者。
        consumers.add(worker);
        return worker;
    }

    /** @return 本机正式回执消费者是否已加入组并继续运行 */
    public boolean receiptsReady() {
        var worker = receiptConsumer;
        return worker != null && worker.ready();
    }

    void stop() {
        receiptConsumer = null;
        RuntimeException failure = null;
        for (var consumer : consumers) {
            try {
                consumer.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }
        }
        consumers.clear();
        if (failure != null) throw failure;
    }

    private void broadcast(List<ConsumerRecord<String, String>> records) {
        for (var record : records) {
            var event = decode(record, codec::accepted);
            Instant sent = Instant.parse(event.sentAt());
            if (sent.plusSeconds(10).isBefore(Instant.now())) continue;
            // 返回 0 表示发布已经执行，只是当前没有订阅者。
            if (redis.convertAndSend(state.channel(event.roomId()), codec.encodeLive(event)) == null) {
                throw new IllegalStateException("Redis 发布结果未知");
            }
            broadcast.record(Duration.between(sent, Instant.now()).abs());
        }
    }

    private void persistHistory(List<ConsumerRecord<String, String>> records) {
        // append 完成 MySQL 事务后才返回，消费循环随后提交本批位点。
        var events = records.stream()
                .map(record -> new HistoryRecord(decode(record, codec::accepted), record.offset())).toList();
        var result = history.append(events);
        var first = records.getFirst();
        for (var conflict : result.conflicts()) {
            String kind = conflict.sequenceMatch() == null ? "message_id"
                    : conflict.messageIdMatch() == null ? "room_sequence" : "both";
            var event = conflict.record().event();
            metrics.counter("liveflow.room.messages.history.conflicts", "kind", kind).increment();
            LOG.warn("跳过弹幕历史冲突 group={} topic={} partition={} offset={} room={} sequence={} "
                            + "incomingMessageId={} sequenceMatch={} messageIdMatch={}",
                    topology.group("history"), first.topic(), first.partition(), conflict.record().offset(), event.roomId(),
                    event.roomSeq(), event.messageId(), conflict.sequenceMatch(), conflict.messageIdMatch());
        }
        Instant now = Instant.now();
        result.inserted().forEach(event -> storage.record(Duration.between(Instant.parse(event.sentAt()), now).abs()));
    }

    private void projectReceipts(List<ConsumerRecord<String, String>> records) {
        for (var record : records) state.project(decode(record, codec::receipt));
    }

    private static <T> T decode(ConsumerRecord<String, String> record,
            BiFunction<String, String, T> decoder) {
        try {
            return decoder.apply(record.key(), record.value());
        } catch (RuntimeException failure) {
            throw new RoomKafkaConsumer.InvalidEvent(record.offset(), failure);
        }
    }
}
