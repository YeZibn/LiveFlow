package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomEvents.Submission;
import com.liveflow.core.room.message.RoomHistoryService;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.message.RoomMessagePorts.Producer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.CloseOptions;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.TaskMetadata;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

/** 管理弹幕 Kafka 主题、事务拓扑和相互独立的外部投影。 */
@Component
public final class RoomKafkaRuntime implements Producer, SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(RoomKafkaRuntime.class);
    private static final int PARTITIONS = 6;
    private static final String RETENTION = "259200000";
    private final Map<String, Object> base;
    private final RoomEventCodec codec;
    private final RoomHistoryService history;
    private final RedisMessageState state;
    private final StringRedisTemplate redis;
    private final String prefix;
    private final String namespace;
    private final String stateDirectory;
    private final short replicas;
    private final int minIsr;
    private final boolean accepting;
    private final boolean enabled;
    private final Semaphore available = new Semaphore(256);
    private final MeterRegistry metrics;
    private final Timer enqueue;
    private final Timer acceptance;
    private final Timer broadcast;
    private final Timer storage;
    private volatile boolean running;
    private volatile KafkaProducer<String, String> producer;
    private volatile KafkaStreams streams;
    private volatile RoomKafkaConsumer receiptConsumer;
    private volatile RoomKafkaConsumer historyConsumer;
    private final List<RoomKafkaConsumer> consumers = new ArrayList<>();
    private final List<AtomicLong> admissionLag = new ArrayList<>();
    private volatile boolean partitionsRestored;

    /**
     * @param kafka Kafka 连接配置入口
     * @param history MySQL 连续历史写入
     * @param state Redis 回执存储及频道规则
     * @param replicas 外部及 Streams 内部主题副本数
     * @param minIsr 外部主题最少同步副本数
     * @param namespace 可选的隔离前缀，空值沿用正式主题和消费组
     * @param accepting 是否开放新的生产，暂停时后台仍可排空
     * @param enabled 是否启动弹幕 Kafka 链路
     */
    public RoomKafkaRuntime(KafkaAdmin kafka, RoomHistoryService history, RedisMessageState state,
            StringRedisTemplate redis, JsonMapper json, MeterRegistry metrics,
            @Value("${liveflow.room-messages.v2.replication-factor:1}") short replicas,
            @Value("${liveflow.room-messages.v2.min-insync-replicas:1}") int minIsr,
            @Value("${liveflow.room-messages.v2.kafka-namespace:}") String namespace,
            @Value("${liveflow.room-messages.v2.state-directory:/tmp/liveflow-room-streams-v2}") String stateDirectory,
            @Value("${liveflow.room-messages.v2.accepting:true}") boolean accepting,
            @Value("${liveflow.room-messages.v2.enabled:true}") boolean enabled) {
        if (replicas < 1 || minIsr < 1 || minIsr > replicas
                || !namespace.matches("[A-Za-z0-9_-]{0,32}") || stateDirectory.isBlank()) {
            throw new IllegalArgumentException("弹幕 Kafka 配置无效");
        }
        this.base = kafka.getConfigurationProperties();
        this.history = history;
        this.state = state;
        this.redis = redis;
        this.codec = new RoomEventCodec(json);
        this.replicas = replicas;
        this.minIsr = minIsr;
        this.prefix = namespace.isEmpty() ? "" : namespace + ".";
        this.namespace = namespace;
        this.stateDirectory = stateDirectory;
        this.metrics = metrics;
        this.accepting = accepting;
        this.enabled = enabled;
        enqueue = stage(metrics, "enqueue");
        acceptance = stage(metrics, "accepted");
        broadcast = stage(metrics, "broadcast");
        storage = stage(metrics, "persisted");
        for (int index = 0; index < PARTITIONS; index++) {
            admissionLag.add(metrics.gauge("liveflow.room.messages.kafka.lag",
                    io.micrometer.core.instrument.Tags.of("group", "admission", "partition", Integer.toString(index)),
                    new AtomicLong(-1)));
        }
    }

    /**

     * @param submission 已获得 Redis 许可的提交

     * @return Broker 写入确认

     */
    @Override
    public CompletableFuture<Void> publish(Submission submission) {
        KafkaProducer<String, String> current = producer;
        KafkaStreams topology = streams;
        if (!running || !accepting || current == null || !ready(topology) || !available.tryAcquire()) {
            throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        }
        var result = new CompletableFuture<Void>();
        long started = System.nanoTime();
        try {
            String json = codec.json().writeValueAsString(submission);
            current.send(new ProducerRecord<>(topic("submissions"), submission.roomId(), json), (metadata, failure) -> {
                available.release();
                if (failure == null) {
                    enqueue.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    result.complete(null);
                } else {
                    result.completeExceptionally(new RoomMessageFailure(MESSAGE_SUBMISSION_UNKNOWN));
                }
            });
        } catch (RuntimeException failure) {
            available.release();
            throw new RoomMessageFailure(failure instanceof KafkaException
                    ? MESSAGE_SUBMISSION_UNKNOWN : ROOM_MESSAGES_UNAVAILABLE);
        }
        return result;
    }

    /** @return 正式接入是否已经恢复本实例负责的全部分区 */
    public boolean admissionReady() {
        return running && ready(streams);
    }

    private boolean ready(KafkaStreams topology) {
        return partitionsRestored && topology != null && topology.state() == KafkaStreams.State.RUNNING;
    }

    /** 单实例准备好全部输入分区之后才能发布。同步采集正式接收组的已提交进度。 */
    @Scheduled(fixedDelay = 500)
    public void observeAdmissionPartitions() {
        KafkaStreams current = streams;
        if (!running || current == null || current.state() != KafkaStreams.State.RUNNING) {
            partitionsRestored = false;
            return;
        }
        List<TaskMetadata> active = current.metadataForLocalThreads().stream()
                .flatMap(thread -> thread.activeTasks().stream()).toList();
        var activePartitions = new java.util.HashSet<TopicPartition>();
        for (var task : active) {
            for (TopicPartition partition : task.topicPartitions()) {
                activePartitions.add(partition);
                Long committed = task.committedOffsets().get(partition);
                Long end = task.endOffsets().get(partition);
                if (end != null && end >= 0) {
                    admissionLag.get(partition.partition()).set(Math.max(0, end - Math.max(0, committed == null ? 0 : committed)));
                }
            }
        }
        partitionsRestored = activePartitions.size() == PARTITIONS;
    }

    /** 收件组未接手分区时，回执接口应报依赖未就绪。 */
    public boolean receiptsReady() {
        var worker = receiptConsumer;
        return worker != null && worker.ready();
    }

    /**

     * @param roomId 规范房间 UUID

     * @return 历史消费位置仍可追平该分区

     */
    public boolean historyAvailable(String roomId) {
        var worker = historyConsumer;
        int hash = org.apache.kafka.common.utils.Utils.toPositive(org.apache.kafka.common.utils.Utils.murmur2(
                roomId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return worker != null && worker.retains(hash % PARTITIONS);
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) return;
        try {
            configureTopics();
            Map<String, Object> producerConfig = new HashMap<>(base);
            producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
            producerConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
            producerConfig.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500);
            producerConfig.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2500);
            producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());

            var streamConfig = new java.util.Properties();
            streamConfig.putAll(base);
            streamConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, group("admission"));
            streamConfig.put(StreamsConfig.STATE_DIR_CONFIG, stateDirectory);
            streamConfig.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
            streamConfig.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, (int) replicas);
            streamConfig.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
            streamConfig.put("topic.min.insync.replicas", minIsr);
            streamConfig.put("topic.unclean.leader.election.enable", "false");
            streams = new KafkaStreams(RoomMessageProcessor.topology(codec, topic("submissions"), topic("accepted"),
                    topic("receipts"), acceptance), streamConfig);
            streams.setUncaughtExceptionHandler(failure -> {
                LOG.error("弹幕事务处理停止 failure={}", failure.getClass().getSimpleName());
                return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_APPLICATION;
            });

            consumers.add(new RoomKafkaConsumer(base, group("broadcast"), "broadcast", topic("accepted"), PARTITIONS, metrics, records -> {
                for (var record : records) {
                    var event = codec.accepted(record.key(), record.value());
                    Instant sent = Instant.parse(event.sentAt());
                    if (sent.plusSeconds(10).isBefore(Instant.now())) continue;
                    // 返回 0 表示发布已经执行，只是当前没有订阅者。
                    if (redis.convertAndSend(state.channel(event.roomId()), codec.encodeLive(event)) == null) {
                        throw new IllegalStateException("Redis 发布结果未知");
                    }
                    broadcast.record(Duration.between(sent, Instant.now()).abs());
                }
            }));
            historyConsumer = new RoomKafkaConsumer(base, group("history"), "history", topic("accepted"), PARTITIONS, metrics, records -> {
                var events = records.stream().map(record -> codec.accepted(record.key(), record.value())).toList();
                history.append(events);
                Instant now = Instant.now();
                events.forEach(event -> storage.record(Duration.between(Instant.parse(event.sentAt()), now).abs()));
            });
            consumers.add(historyConsumer);
            receiptConsumer = new RoomKafkaConsumer(base, group("receipt"), "receipt", topic("receipts"), PARTITIONS, metrics, records -> {
                for (var record : records) state.project(codec.receipt(record.key(), record.value()));
            });
            consumers.add(receiptConsumer);
            streams.start();
            running = true;
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("弹幕 Kafka 运行时初始化失败", failure);
        }
    }

    /**
     * 在 Redis 回执命名空间缺少恢复标记时，独立重放查询期内的事件。
     * 正常投影组保留自己的消费位置，过期事件由 Redis 脚本跳过。
     */
    @Scheduled(fixedDelayString = "${liveflow.room-messages.v2.receipt-recovery-millis:30000}")
    public void restoreReceipts() {
        if (!running) return;
        String marker = state.receiptProjectionMarker(namespace);
        try {
            if (Boolean.TRUE.equals(redis.expire(marker, Duration.ofMinutes(2)))) return;
            restoreReceiptWindow();
            redis.opsForValue().set(marker, "1", Duration.ofMinutes(2));
        } catch (RuntimeException failure) {
            LOG.warn("弹幕回执恢复将在下一轮重试 failure={}", failure.getClass().getSimpleName());
        }
    }

    private void restoreReceiptWindow() {
        var props = RoomKafkaConsumer.config(base, group("receipt-rebuild"));
        String topic = topic("receipts");
        try (var replay = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            var partitions = java.util.stream.IntStream.range(0, PARTITIONS)
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
                if (!running || System.nanoTime() >= deadline) throw new IllegalStateException("回执回放未完成");
                for (var record : replay.poll(Duration.ofMillis(200))) {
                    if (record.offset() < ends.get(new TopicPartition(topic, record.partition()))) {
                        state.project(codec.receipt(record.key(), record.value()));
                    }
                }
            }
        }
    }

    private void configureTopics() throws Exception {
        List<String> topics = List.of(topic("submissions"), topic("accepted"), topic("receipts"));
        try (Admin admin = Admin.create(base)) {
            Set<String> existing = admin.listTopics().names().get(5, TimeUnit.SECONDS);
            var missing = topics.stream().filter(name -> !existing.contains(name))
                    .map(name -> new NewTopic(name, PARTITIONS, replicas).configs(topicConfig(name))).toList();
            if (!missing.isEmpty()) admin.createTopics(missing).all().get(8, TimeUnit.SECONDS);
            var descriptions = admin.describeTopics(topics).allTopicNames().get(8, TimeUnit.SECONDS);
            var resources = topics.stream().map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name)).toList();
            var configs = admin.describeConfigs(resources).all().get(8, TimeUnit.SECONDS);
            for (var resource : resources) {
                var partitions = descriptions.get(resource.name()).partitions();
                if (partitions.size() != PARTITIONS || partitions.stream().anyMatch(p -> p.replicas().size() != replicas)) {
                    throw new IllegalStateException("弹幕主题分区或副本配置不符 " + resource.name());
                }
                topicConfig(resource.name()).forEach((key, expected) -> {
                    String actual = configs.get(resource).get(key).value();
                    boolean matches = actual.equals(expected) || (key.equals(TopicConfig.CLEANUP_POLICY_CONFIG)
                            && Set.of(actual.split(",")).equals(Set.of(expected.split(","))));
                    if (!matches) throw new IllegalStateException("弹幕主题配置不符 " + resource.name() + " " + key);
                });
            }
        }
    }

    private Map<String, String> topicConfig(String name) {
        return Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, name.equals(topic("receipts")) ? "compact,delete" : "delete",
                TopicConfig.RETENTION_MS_CONFIG, RETENTION, TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(minIsr),
                TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "false");
    }

    private String topic(String kind) {
        return prefix + "liveflow.room-message." + kind + ".v1";
    }

    private String group(String kind) {
        return prefix + "liveflow-room-message-" + kind + "-v1";
    }

    private static Timer stage(MeterRegistry registry, String stage) {
        Timer timer = Timer.builder("liveflow.room.messages.stage").tag("stage", stage)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
        for (String quantile : List.of("0.5", "0.95", "0.99")) {
            double percentile = Double.parseDouble(quantile);
            io.micrometer.core.instrument.Gauge.builder("liveflow.room.messages.stage.percentile", timer, source ->
                    java.util.Arrays.stream(source.takeSnapshot().percentileValues())
                            .filter(value -> value.percentile() == percentile).findFirst()
                            .map(value -> value.value(TimeUnit.MILLISECONDS)).orElse(Double.NaN))
                    .baseUnit("milliseconds").tags("stage", stage, "quantile", quantile).register(registry);
        }
        return timer;
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (producer != null) producer.close(Duration.ofSeconds(3));
        if (streams != null) streams.close(CloseOptions.timeout(Duration.ofSeconds(12))
                .withGroupMembershipOperation(CloseOptions.GroupMembershipOperation.LEAVE_GROUP));
        consumers.forEach(RoomKafkaConsumer::close);
        consumers.clear();
        producer = null;
        streams = null;
        receiptConsumer = null;
        historyConsumer = null;
        partitionsRestored = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
