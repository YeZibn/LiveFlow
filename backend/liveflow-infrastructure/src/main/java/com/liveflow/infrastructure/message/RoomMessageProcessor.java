package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomEvents;
import com.liveflow.core.room.message.RoomEvents.Accepted;
import com.liveflow.core.room.message.RoomEvents.Receipt;
import com.liveflow.core.room.message.RoomEvents.Submission;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按 Kafka 输入分区裁决提交，以 Streams 事务同时提交状态、正式事件和回执。 */
final class RoomMessageProcessor implements Processor<String, String, String, String> {
    private static final Logger LOG = LoggerFactory.getLogger(RoomMessageProcessor.class);
    private static final String SEQUENCES = "room-sequences";
    private static final String DEDUP = "room-dedup";
    private static final String OUTCOMES = "room-outcomes";
    private static final String EXPIRATIONS = "room-expirations";
    private final RoomEventCodec codec;
    private ProcessorContext<String, String> context;
    private KeyValueStore<String, String> sequences;
    private KeyValueStore<String, String> dedup;
    private KeyValueStore<String, String> outcomes;
    private KeyValueStore<String, String> expirations;
    private final Timer acceptance;
    private final RoomProcessingBlocks blocks;
    private final RoomMessageTopology layout;

    private RoomMessageProcessor(RoomEventCodec codec, Timer acceptance, RoomProcessingBlocks blocks, RoomMessageTopology layout) {
        this.codec = codec;
        this.acceptance = acceptance;
        this.blocks = blocks;
        this.layout = layout;
    }

    /**
     * @param codec 输入输出事件编解码
     * @param submissions 提交主题
     * @param accepted 正式事件主题
     * @param receipts 终态回执主题
     * @param acceptance 正式接收延迟
     * @param blocks 接任方读取的持久阻断
     * @param layout Kafka 职责与命名
     * @return 绑定业务主题与四个可恢复状态库的拓扑
     */
    static Topology topology(RoomEventCodec codec, String submissions, String accepted, String receipts,
            Timer acceptance, RoomProcessingBlocks blocks, RoomMessageTopology layout) {
        Topology topology = new Topology()
                .addSource("submissions", new StringDeserializer(), new StringDeserializer(), submissions)
                .addProcessor("admit", () -> new RoomMessageProcessor(codec, acceptance, blocks, layout), "submissions")
                .addSink("accepted", accepted, new StringSerializer(), new StringSerializer(), "admit")
                .addSink("receipts", receipts, new StringSerializer(), new StringSerializer(), "admit");
        for (String store : new String[]{SEQUENCES, DEDUP, OUTCOMES, EXPIRATIONS}) {
            topology.addStateStore(Stores.keyValueStoreBuilder(
                    Stores.persistentKeyValueStore(store), Serdes.String(), Serdes.String()), "admit");
        }
        return topology;
    }

    @Override
    public void init(ProcessorContext<String, String> context) {
        this.context = context;
        sequences = context.getStateStore(SEQUENCES);
        dedup = context.getStateStore(DEDUP);
        outcomes = context.getStateStore(OUTCOMES);
        expirations = context.getStateStore(EXPIRATIONS);
        context.schedule(Duration.ofSeconds(1), PunctuationType.WALL_CLOCK_TIME, this::expire);
    }

    /**
     * @param record 分区中的下一条提交
     * @throws RuntimeException 事件不合法或状态失败，交由 Streams 停止处理并保留未确认进度
     */
    @Override
    public void process(Record<String, String> record) {
        Submission input;
        try {
            input = codec.submission(record.key(), record.value());
        } catch (RuntimeException invalid) {
            context.recordMetadata().ifPresent(meta -> {
                // 提交的 Streams 事务随异常回滚；共享阻断单独提交，接任后不再投递此分区。
                blocks.record(layout.group("admission"), meta.topic(), meta.partition(), "",
                        "PROCESSING_ERROR", meta.offset());
                LOG.error("弹幕提交契约无效 topic={} partition={} offset={}", meta.topic(), meta.partition(), meta.offset());
            });
            throw invalid;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant admitted = Instant.parse(input.admittedAt());
        Instant deadline = admitted.plusSeconds(RoomEvents.RECEIPT_SECONDS);
        String attempt = input.submissionId().toString();
        if (!now.isBefore(deadline) || outcomes.get(attempt) != null) return;

        // 同房间、同会话和同客户端标识共享首次正式结果，新的预约仍得到自己的查询期限。
        String businessKey = input.sessionId() + ":" + input.roomId() + ":" + input.clientMessageId();
        String prior = dedup.get(businessKey);
        Accepted accepted = null;
        String code = null;
        if (prior != null) {
            Canonical original = codec.json().readValue(prior, Canonical.class);
            if (now.toEpochMilli() < original.expiresAt()) {
                if (original.hash().equals(input.contentHash())) accepted = original.event();
                else code = "MESSAGE_ID_CONFLICT";
            }
        }
        if (accepted == null && code == null) {
            if (!now.isBefore(admitted.plusSeconds(RoomEvents.ACCEPT_SECONDS))) {
                code = "MESSAGE_SUBMISSION_EXPIRED";
            } else {
                accepted = accept(input, now, businessKey);
                acceptance.record(Duration.between(admitted, now).abs());
            }
        }

        Receipt receipt = new Receipt(1, input.submissionId(), input.roomId(), input.userId(), input.sessionId(),
                accepted == null ? "rejected" : "accepted", accepted == null ? null : accepted.messageId(),
                accepted == null ? null : accepted.roomSeq(), accepted == null ? null : accepted.sentAt(), code,
                RoomEvents.UTC_MILLIS.format(deadline));
        String json = codec.json().writeValueAsString(receipt);
        outcomes.put(attempt, json);
        expirations.put(expiryKey(deadline.toEpochMilli(), "o", attempt), attempt);
        context.forward(new Record<>(attempt, json, now.toEpochMilli()), "receipts");
    }

    private Accepted accept(Submission input, Instant now, String businessKey) {
        String room = input.roomId();
        String previous = sequences.get(room);
        long sequence = previous == null ? 1 : Long.parseLong(previous) + 1;
        if (sequence > RoomEvents.MAX_SEQUENCE) throw new IllegalStateException("房间序号已耗尽");
        String value = Long.toString(sequence);
        var event = new Accepted(1, room, value, UUID.randomUUID(), input.userId(), input.senderName(),
                input.content(), RoomEvents.UTC_MILLIS.format(now));
        long expiration = now.plusSeconds(RoomEvents.RECEIPT_SECONDS).toEpochMilli();

        // 所有状态变更和输出都属于输入 offset 所在的事务。
        sequences.put(room, value);
        dedup.put(businessKey, codec.json().writeValueAsString(new Canonical(input.contentHash(), event, expiration)));
        expirations.put(expiryKey(expiration, "d", businessKey), businessKey);
        context.forward(new Record<>(room, codec.json().writeValueAsString(event), now.toEpochMilli()), "accepted");
        return event;
    }

    private void expire(long now) {
        // 每次只清理有界的已到期前缀，热分区不会因遍历整个状态库而阻塞。
        var expired = new ArrayList<KeyValue<String, String>>();
        try (var iterator = expirations.range("", expiryKey(now, "~", "~"))) {
            while (iterator.hasNext() && expired.size() < 500) expired.add(iterator.next());
        }
        for (var entry : expired) {
            if (entry.key.charAt(20) == 'o') {
                outcomes.delete(entry.value);
            } else {
                String value = dedup.get(entry.value);
                if (value != null && codec.json().readValue(value, Canonical.class).expiresAt() <= now) {
                    dedup.delete(entry.value);
                }
            }
            expirations.delete(entry.key);
        }
    }

    private static String expiryKey(long millis, String kind, String id) {
        return String.format(Locale.ROOT, "%019d:%s:%s", millis, kind, id);
    }

    record Canonical(String hash, Accepted event, long expiresAt) {}
}
