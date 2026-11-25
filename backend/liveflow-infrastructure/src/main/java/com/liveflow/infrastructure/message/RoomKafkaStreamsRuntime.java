package com.liveflow.infrastructure.message;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.CloseOptions;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TaskMetadata;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.infrastructure.message.RoomMessageTopology.PARTITIONS;

/** 管理正式受理拓扑、分区探测和实例准入快照。 */
@Component
public final class RoomKafkaStreamsRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(RoomKafkaStreamsRuntime.class);
    private final RoomKafkaSettings settings;
    private final RoomMessageTopology topology;
    private final RoomMessageInstance instance;
    private final RoomAdmissionStatus admission;
    private final RoomProcessingBlocks blocks;
    private final RoomEventCodec codec;
    private final Timer acceptance;
    private final List<AtomicLong> admissionLag = new ArrayList<>();
    private volatile boolean running;
    private RoomAdmissionProbe probe;
    private KafkaStreams streams;

    RoomKafkaStreamsRuntime(RoomKafkaSettings settings, RoomMessageTopology topology, RoomMessageInstance instance,
            RoomAdmissionStatus admission, RoomProcessingBlocks blocks, JsonMapper json, MeterRegistry metrics) {
        this.settings = settings;
        this.topology = topology;
        this.instance = instance;
        this.admission = admission;
        this.blocks = blocks;
        codec = new RoomEventCodec(json);
        acceptance = RoomKafkaMetrics.stage(metrics, "accepted");
        for (int index = 0; index < PARTITIONS; index++) {
            admissionLag.add(metrics.gauge("liveflow.room.messages.kafka.lag",
                    Tags.of("group", "admission", "partition", Integer.toString(index)), new AtomicLong(-1)));
        }
    }

    /** 准备正式受理拓扑，输出消费组启动后再开始处理。 */
    void prepare() {
        probe = new RoomAdmissionProbe(settings.consumerBase, topology.group("admission"), codec);
        var streamConfig = new Properties();
        streamConfig.putAll(settings.base);
        streamConfig.put(StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG),
                settings.consumerBase.get(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG));
        streamConfig.put(StreamsConfig.consumerPrefix(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG),
                settings.consumerBase.get(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG));
        streamConfig.put(StreamsConfig.CLIENT_ID_CONFIG, "liveflow-" + instance.id());
        streamConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, topology.group("admission"));
        streamConfig.put(StreamsConfig.STATE_DIR_CONFIG, settings.stateDirectory);
        streamConfig.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        streamConfig.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, (int) settings.replicas);
        streamConfig.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
        streamConfig.put("topic.min.insync.replicas", settings.minIsr);
        streamConfig.put("topic.unclean.leader.election.enable", "false");
        streams = new KafkaStreams(RoomMessageProcessor.topology(codec, topology.topic("submissions"), topology.topic("accepted"),
                topology.topic("receipts"), acceptance, blocks, topology), streamConfig, new DefaultKafkaClientSupplier() {
            @Override
            public Consumer<byte[], byte[]> getConsumer(Map<String, Object> config) {
                return new RoomStreamsConsumer(config, blocks, topology);
            }
        });
        streams.setUncaughtExceptionHandler(failure -> {
            LOG.error("本机弹幕事务处理停止 instance={}", instance.id(), failure);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
        });
    }

    synchronized void start() {
        streams.start();
        running = true;
    }

    /** 和分区采样使用同一把锁，防止退出后的采样重新发布本机准入。 */
    synchronized void withdrawAdmission() {
        if (running) {
            running = false;
            admission.leaving();
        }
    }

    void stop() {
        var currentProbe = probe;
        probe = null;
        var currentStreams = streams;
        streams = null;
        try {
            if (currentProbe != null) currentProbe.close();
        } finally {
            if (currentStreams != null) {
                currentStreams.close(CloseOptions.timeout(Duration.ofSeconds(12))
                        .withGroupMembershipOperation(CloseOptions.GroupMembershipOperation.LEAVE_GROUP));
            }
        }
    }

    /** @return 当前入口是否观察到所有提交分区可接纳新预约 */
    public boolean admissionReady() {
        return running && IntStream.range(0, PARTITIONS).allMatch(admission::allows);
    }

    /** 周期记录本机已恢复分区并抽样积压。交接期间只发布可确知的状态。 */
    @Scheduled(fixedDelay = 1000)
    public synchronized void observeAdmissionPartitions() {
        if (!running) return;
        long sampledAt = System.currentTimeMillis();
        var local = new HashMap<Integer, RoomAdmissionStatus.Lag>();
        KafkaStreams current = streams;
        if (current != null && current.state() == KafkaStreams.State.RUNNING) {
            var assigned = new HashSet<TopicPartition>();
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

}
