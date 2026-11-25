package com.liveflow.infrastructure.message;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import static com.liveflow.infrastructure.message.RoomMessageTopology.PARTITIONS;

/** 校验业务 Topic 并统一协调弹幕 Kafka 组件的生命周期。 */
@Component
public final class RoomKafkaRuntime implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(RoomKafkaRuntime.class);
    private static final String RETENTION = "259200000";
    private final RoomKafkaSettings settings;
    private final RoomMessageTopology topology;
    private final RoomKafkaProducer producer;
    private final RoomKafkaStreamsRuntime streams;
    private final RoomKafkaConsumerGroupRuntime consumers;
    private final RoomKafkaMaintenanceScheduler maintenance;
    private volatile boolean running;

    /**
     * @param settings Kafka 连接及运行参数
     * @param topology 业务 Topic 和分区规则
     * @param producer 提交生产者
     * @param streams 正式受理及分区探测
     * @param consumers 正式输出消费组
     * @param maintenance 回执重建与历史清理
     */
    RoomKafkaRuntime(RoomKafkaSettings settings, RoomMessageTopology topology, RoomKafkaProducer producer,
            RoomKafkaStreamsRuntime streams, RoomKafkaConsumerGroupRuntime consumers,
            RoomKafkaMaintenanceScheduler maintenance) {
        this.settings = settings;
        this.topology = topology;
        this.producer = producer;
        this.streams = streams;
        this.consumers = consumers;
        this.maintenance = maintenance;
    }

    /**
     * 校验业务 Topic 后按依赖顺序启动。只有组件都已启动才开放本机生产入口。
     * @throws IllegalStateException Topic 校验或组件启动失败，原错误保留在异常链
     */
    @Override
    public synchronized void start() {
        if (!settings.enabled || running) return;
        try {
            configureTopics();
            producer.prepare();
            streams.prepare();
            consumers.start();
            streams.start();
            maintenance.start();
            producer.openAdmission();
            running = true;
        } catch (Exception failure) {
            stopComponents(failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("弹幕 Kafka 运行时初始化失败", failure);
        }
    }

    /** @return 早于 HTTP 启动，并在接入和连接排空后关闭 Kafka */
    @Override
    public int getPhase() {
        return 0;
    }

    /** 关闭入口和定时工作，再依次清理各组件持有的客户端。 */
    @Override
    public synchronized void stop() {
        if (running) stopComponents(null);
    }

    /** @return 本机 Kafka 组件是否已完成启动，不代表所有分区均可接入 */
    @Override
    public boolean isRunning() {
        return running;
    }

    private void stopComponents(Exception startFailure) {
        running = false;
        producer.closeAdmission();
        maintenance.stop();
        // 停止探测并发布退出状态之后，再关客户端，避免迟到的采样恢复本机准入。
        cleanup("分区准入", streams::withdrawAdmission, startFailure);
        cleanup("生产者", producer::stop, startFailure);
        cleanup("Streams", streams::stop, startFailure);
        cleanup("消费组", consumers::stop, startFailure);
    }

    private static void cleanup(String component, Runnable action, Exception startFailure) {
        try {
            action.run();
        } catch (RuntimeException closeFailure) {
            if (startFailure != null && closeFailure != startFailure) startFailure.addSuppressed(closeFailure);
            LOG.warn("弹幕 Kafka 组件关闭失败 component={}", component, closeFailure);
        }
    }

    /** @throws Exception 创建主题失败或现有主题配置与本实例设置不一致 */
    void configureTopics() throws Exception {
        List<String> topics = List.of(topic("submissions"), topic("accepted"), topic("receipts"));
        try (Admin admin = Admin.create(settings.base)) {
            Set<String> existing = admin.listTopics().names().get(5, TimeUnit.SECONDS);
            var missing = topics.stream().filter(name -> !existing.contains(name))
                    .map(name -> new NewTopic(name, PARTITIONS, settings.replicas).configs(topicConfig(name))).toList();
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
                if (partitions.size() != PARTITIONS || partitions.stream().anyMatch(p -> p.replicas().size() != settings.replicas)) {
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
                TopicConfig.RETENTION_MS_CONFIG, RETENTION, TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(settings.minIsr),
                TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "false");
    }

    private String topic(String kind) {
        return topology.topic(kind);
    }

}
