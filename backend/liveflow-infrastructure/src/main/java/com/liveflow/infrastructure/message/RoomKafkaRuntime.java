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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier;
import org.apache.kafka.common.errors.TopicExistsException;
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
import static com.liveflow.infrastructure.message.RoomMessageTopology.PARTITIONS;
import static com.liveflow.core.resilience.RoomMessageCircuitBreaker.Dependency.KAFKA;

/** 管理弹幕 Kafka 主题、事务拓扑和相互独立的外部投影。 */
@Component
public final class RoomKafkaRuntime implements Producer, SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(RoomKafkaRuntime.class);
    private static final String RETENTION = "259200000";
    private final Map<String, Object> base;
    private final Map<String, Object> consumerBase;
    private final RoomEventCodec codec;
    private final RoomHistoryService history;
    private final RedisMessageState state;
    private final StringRedisTemplate redis;
    private final RoomMessageTopology topology;
    private final RoomMessageInstance instance;
    private final RoomAdmissionStatus admission;
    private final RoomProcessingBlocks blocks;
    private final RoomMaintenanceLease maintenance;
    private RoomAdmissionProbe probe;
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
    private final List<RoomKafkaConsumer> consumers = new ArrayList<>();
    private final List<AtomicLong> admissionLag = new ArrayList<>();

    /**
     * @param kafka Kafka 连接配置入口
     * @param history MySQL 连续历史写入
     * @param state Redis 回执存储及频道规则
     * @param redis 频道发布与回执恢复标记
     * @param json 弹幕事件序列化
     * @param metrics 按阶段记录入队、正式接收和外部副作用
     * @param replicas 外部及 Streams 内部主题副本数
     * @param minIsr 外部主题最少同步副本数
     * @param stateDirectory 当前实例独占的 Streams 状态目录
     * @param topology 共用 Kafka 命名及房间分区
     * @param instance 当前运行实例身份
     * @param admission 按目标分区的集群接入状态
     * @param blocks 可随接管恢复的持久处理阻断
     * @param maintenance 回执重建及历史清理租约
     * @param accepting 是否开放新的生产，暂停时后台仍可排空
     * @param enabled 是否启动弹幕 Kafka 链路
     * @param sessionMillis 消费组识别异常退出的会话期限
     * @param heartbeatMillis 消费组经典协议的心跳周期
     */
    public RoomKafkaRuntime(KafkaAdmin kafka, RoomHistoryService history, RedisMessageState state,
            StringRedisTemplate redis, JsonMapper json, MeterRegistry metrics,
            RoomMessageTopology topology, RoomMessageInstance instance, RoomAdmissionStatus admission,
            RoomProcessingBlocks blocks, RoomMaintenanceLease maintenance,
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
        this.base = kafka.getConfigurationProperties();
        this.consumerBase = new HashMap<>(base);
        consumerBase.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionMillis);
        consumerBase.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatMillis);
        this.history = history;
        this.state = state;
        this.redis = redis;
        this.codec = new RoomEventCodec(json);
        this.replicas = replicas;
        this.minIsr = minIsr;
        this.topology = topology;
        this.instance = instance;
        this.admission = admission;
        this.blocks = blocks;
        this.maintenance = maintenance;
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
     * 将本次预约交给生产者一次，并在实际确认结束时归还生产并发额度。
     * @param submission 已获得 Redis 单次发布许可的提交
     * @return Broker 写入确认，结果未知时异常完成
     * @throws RoomMessageFailure 本机拒绝或 Kafka 接入不可用
     */
    @Override
    public CompletableFuture<Void> publish(Submission submission) {
        KafkaProducer<String, String> current = producer;
        if (!enabled || !accepting) {
            throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        }
        if (!running || current == null) {
            throw RoomMessageFailure.dependency(ROOM_MESSAGES_UNAVAILABLE, KAFKA, null);
        }
        if (!available.tryAcquire()) throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        var result = new CompletableFuture<Void>();
        // 同步异常和异步回调共用一次完成边界，避免提前归还或者重复归还生产额度。
        var completion = result.whenComplete((ignored, failure) -> available.release());
        long started = System.nanoTime();
        try {
            String json = codec.json().writeValueAsString(submission);
            current.send(new ProducerRecord<>(topic("submissions"), RoomMessageTopology.partition(submission.roomId()),
                    submission.roomId(), json), (metadata, failure) -> {
                if (failure == null) {
                    enqueue.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    result.complete(null);
                } else {
                    result.completeExceptionally(RoomMessageFailure.dependency(MESSAGE_SUBMISSION_UNKNOWN, KAFKA, failure));
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(switch (failure) {
                case KafkaException kafkaFailure -> RoomMessageFailure.dependency(MESSAGE_SUBMISSION_UNKNOWN, KAFKA, kafkaFailure);
                case IllegalStateException closed -> RoomMessageFailure.dependency(ROOM_MESSAGES_UNAVAILABLE, KAFKA, closed);
                default -> new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
            });
        }
        return completion;
    }

    /** @param roomId 目标房间
     * @throws RoomMessageFailure 本机停写或目标分区暂不可接入
     */
    @Override
    public void requireAdmission(String roomId) {
        if (!running || !enabled || !accepting || producer == null) {
            throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        }
        admission.require(RoomMessageTopology.partition(roomId));
    }

    /** @return 当前入口是否观察到所有提交分区可接纳新预约 */
    public boolean admissionReady() {
        return running && java.util.stream.IntStream.range(0, PARTITIONS).allMatch(admission::allows);
    }

    /** 周期记录本机已恢复分区并抽样积压。交接期间只发布可确知的状态。 */
    @Scheduled(fixedDelay = 1000)
    public synchronized void observeAdmissionPartitions() {
        if (!running) return;
        long sampledAt = System.currentTimeMillis();
        var local = new HashMap<Integer, RoomAdmissionStatus.Lag>();
        KafkaStreams current = streams;
        if (current != null && current.state() == KafkaStreams.State.RUNNING) {
            var assigned = new java.util.HashSet<TopicPartition>();
            for (TaskMetadata task : current.metadataForLocalThreads().stream()
                    .flatMap(thread -> thread.activeTasks().stream()).toList()) {
                for (TopicPartition partition : task.topicPartitions()) {
                    assigned.add(partition);
                    Long committed = task.committedOffsets().get(partition);
                    Long end = task.endOffsets().get(partition);
                    if (end != null && end >= 0) {
                        admissionLag.get(partition.partition()).set(Math.max(0, end - Math.max(0, committed == null ? 0 : committed)));
                    }
                }
            }
            try {
                local.putAll(probe.observe(assigned));
            } catch (RuntimeException failure) {
                // 不以探测超时刷新之前分区的有效期。
                LOG.warn("弹幕积压探测失败 instance={} failure={}", instance.id(), failure.getClass().getSimpleName());
            }
            if (current.state() != KafkaStreams.State.RUNNING) local.clear();
        }
        admission.refresh(sampledAt, local);
    }

    /** @return 本机回执消费者是否已加入组，用于独立运行观测 */
    public boolean receiptsReady() {
        var worker = receiptConsumer;
        return worker != null && worker.ready();
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) return;
        try {
            configureTopics();
            probe = new RoomAdmissionProbe(consumerBase, group("admission"), codec);
            Map<String, Object> producerConfig = new HashMap<>(base);
            producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
            producerConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
            producerConfig.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500);
            producerConfig.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2500);
            producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());

            var streamConfig = new java.util.Properties();
            streamConfig.putAll(base);
            streamConfig.put(StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG),
                    consumerBase.get(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG));
            streamConfig.put(StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG),
                    consumerBase.get(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG));
            streamConfig.put(StreamsConfig.CLIENT_ID_CONFIG, "liveflow-" + instance.id());
            streamConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, group("admission"));
            streamConfig.put(StreamsConfig.STATE_DIR_CONFIG, stateDirectory);
            streamConfig.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
            streamConfig.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, (int) replicas);
            streamConfig.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
            streamConfig.put("topic.min.insync.replicas", minIsr);
            streamConfig.put("topic.unclean.leader.election.enable", "false");
            streams = new KafkaStreams(RoomMessageProcessor.topology(codec, topic("submissions"), topic("accepted"),
                    topic("receipts"), acceptance, blocks, topology), streamConfig, new DefaultKafkaClientSupplier() {
                @Override
                public Consumer<byte[], byte[]> getConsumer(Map<String, Object> config) {
                    return new RoomStreamsConsumer(config, blocks, topology);
                }
            });
            streams.setUncaughtExceptionHandler(failure -> {
                LOG.error("本机弹幕事务处理停止 instance={}", instance.id(), failure);
                return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
            });

            consumers.add(new RoomKafkaConsumer(consumerBase, group("broadcast"), "broadcast", topic("accepted"), PARTITIONS, metrics, blocks, records -> {
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
            }));
            consumers.add(new RoomKafkaConsumer(consumerBase, group("history"), "history", topic("accepted"), PARTITIONS, metrics, blocks, records -> {
                var events = records.stream().map(record -> decode(record, codec::accepted)).toList();
                history.append(events, records.getFirst().offset());
                Instant now = Instant.now();
                events.forEach(event -> storage.record(Duration.between(Instant.parse(event.sentAt()), now).abs()));
            }));
            receiptConsumer = new RoomKafkaConsumer(consumerBase, group("receipt"), "receipt", topic("receipts"), PARTITIONS, metrics, blocks, records -> {
                for (var record : records) state.project(decode(record, codec::receipt));
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
        String marker = state.receiptProjectionMarker(topology.namespace());
        try {
            if (Boolean.TRUE.equals(redis.expire(marker, Duration.ofMinutes(2)))) return;
            var acquired = maintenance.acquire("receipt-rebuild");
            if (acquired.isEmpty()) return;
            try (var lease = acquired.get()) {
                if (Boolean.TRUE.equals(redis.expire(marker, Duration.ofMinutes(2)))) return;
                restoreReceiptWindow(lease);
                if (!lease.complete(marker, Duration.ofMinutes(2))) {
                    throw new IllegalStateException("回执恢复租约已失效");
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("弹幕回执恢复将在下一轮重试 failure={}", failure.getClass().getSimpleName());
        }
    }

    /** 按共享租约清理过期历史，各实例仍独立通知自己的水位。 */
    @Scheduled(fixedDelayString = "${liveflow.room-messages.v2.history-cleanup-millis:30000}")
    public void cleanHistory() {
        if (!running) return;
        try {
            var acquired = maintenance.acquire("history-cleanup");
            if (acquired.isEmpty()) return;
            try (var lease = acquired.get()) {
                history.clean(() -> running && lease.renew());
            }
        } catch (RuntimeException failure) {
            LOG.warn("弹幕历史清理将在下一轮恢复 instance={} failure={}", instance.id(), failure.getClass().getSimpleName());
        }
    }

    private void restoreReceiptWindow(RoomMaintenanceLease.Lease lease) {
        var props = RoomKafkaConsumer.config(consumerBase, group("receipt-rebuild"));
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
                if (!running || System.nanoTime() >= deadline || !lease.renew()) throw new IllegalStateException("回执回放未完成");
                for (var record : replay.poll(Duration.ofMillis(200))) {
                    if (record.offset() < ends.get(new TopicPartition(topic, record.partition()))) {
                        state.project(codec.receipt(record.key(), record.value()));
                    }
                }
            }
        }
    }

    private static <T> T decode(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
            java.util.function.BiFunction<String, String, T> decoder) {
        try {
            return decoder.apply(record.key(), record.value());
        } catch (RuntimeException failure) {
            throw new RoomKafkaConsumer.InvalidEvent(record.offset(), failure);
        }
    }

    private void configureTopics() throws Exception {
        List<String> topics = List.of(topic("submissions"), topic("accepted"), topic("receipts"));
        try (Admin admin = Admin.create(base)) {
            Set<String> existing = admin.listTopics().names().get(5, TimeUnit.SECONDS);
            var missing = topics.stream().filter(name -> !existing.contains(name))
                    .map(name -> new NewTopic(name, PARTITIONS, replicas).configs(topicConfig(name))).toList();
            if (!missing.isEmpty()) {
                for (var result : admin.createTopics(missing).values().values()) {
                    try {
                        result.get(8, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException failure) {
                        // 三台可同时启动。已被同伴创建的主题交给下面同一套配置检查。
                        if (!(failure.getCause() instanceof TopicExistsException)) throw failure;
                    }
                }
            }
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
        return topology.topic(kind);
    }

    private String group(String kind) {
        return topology.group(kind);
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

    /** @return 早于 HTTP 启动，并在接入和连接排空后关闭 Kafka。 */
    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public synchronized void stop() {
        if (running) admission.leaving();
        running = false;
        if (probe != null) {
            probe.close();
            probe = null;
        }
        if (producer != null) producer.close(Duration.ofSeconds(3));
        if (streams != null) streams.close(CloseOptions.timeout(Duration.ofSeconds(12))
                .withGroupMembershipOperation(CloseOptions.GroupMembershipOperation.LEAVE_GROUP));
        consumers.forEach(RoomKafkaConsumer::close);
        consumers.clear();
        producer = null;
        streams = null;
        receiptConsumer = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
