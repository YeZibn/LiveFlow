package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomMessageFailure;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.ROOM_MESSAGES_UNAVAILABLE;

/** 将分区运行状态缓存到接入进程，HTTP 请求不直接访问 Kafka 或集群状态存储。 */
@Component
final class RoomAdmissionStatus {
    private static final Logger LOG = LoggerFactory.getLogger(RoomAdmissionStatus.class);
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final RoomMessageInstance instance;
    private final RoomProcessingBlocks blocks;
    private final RoomMessageTopology topology;
    private final String prefix;
    private final Clock clock = Clock.systemUTC();
    private volatile BlockView durable = new BlockView(0, Set.of());
    private volatile Map<String, Snapshot> members = Map.of();
    private volatile Map<Integer, Observation> lastReady = Map.of();

    /** @param redis 共享运行快照
     * @param json 快照序列化
     * @param instance 本机身份和部署时限
     * @param blocks 正式接收的持久阻断
     * @param topology 共用 Kafka 命名
     * @param environment 共用 Redis 环境名
     */
    RoomAdmissionStatus(StringRedisTemplate redis, JsonMapper json, RoomMessageInstance instance,
            RoomProcessingBlocks blocks, RoomMessageTopology topology,
            @Value("${liveflow.room-messages.v2.environment:local}") String environment) {
        this.redis = redis;
        this.json = json;
        this.instance = instance;
        this.blocks = blocks;
        this.topology = topology;
        prefix = environment + ":liveflow:rooms:message:runtime:" + topology.namespace() + ":";
    }

    /** @param observedAt 采样开始时间
     * @param local 已恢复的本机任务及其最老积压采样
     */
    void refresh(long observedAt, Map<Integer, Lag> local) {
        try {
            long readStarted = clock.millis();
            var blocked = blocks.partitions(topology.group("admission"), topology.topic("submissions"));
            durable = new BlockView(readStarted, blocked);
        } catch (RuntimeException failure) {
            LOG.warn("弹幕正式接收阻断状态刷新失败 instance={} failure={}", instance.id(), failure.getClass().getSimpleName());
        }
        try {
            var snapshot = new Snapshot(instance.bootId(), observedAt, local);
            redis.opsForValue().set(prefix + instance.id(), json.writeValueAsString(snapshot), instance.freshness());
        } catch (RuntimeException failure) {
            LOG.warn("弹幕运行快照发布失败 instance={} failure={}", instance.id(), failure.getClass().getSimpleName());
        }
        try {
            var active = new HashMap<String, Snapshot>();
            var previous = new HashMap<>(lastReady);
            for (String member : instance.members()) {
                String value = redis.opsForValue().get(prefix + member);
                if (value == null) continue;
                Snapshot snapshot = json.readValue(value, Snapshot.class);
                if (!fresh(snapshot.observedAt(), clock.millis())) continue;
                active.put(member, snapshot);
                snapshot.partitions().forEach((partition, lag) -> {
                    var latest = previous.get(partition);
                    if (latest == null || latest.observedAt() < snapshot.observedAt()) {
                        previous.put(partition, new Observation(member, snapshot.bootId(), snapshot.observedAt(), lag));
                    }
                });
            }
            members = Map.copyOf(active);
            lastReady = Map.copyOf(previous);
        } catch (RuntimeException failure) {
            LOG.warn("弹幕运行快照读取失败 instance={} failure={}", instance.id(), failure.getClass().getSimpleName());
        }
    }

    /** 发布无活动任务的最终快照。异常退出时由旧快照的有效期限制影响。 */
    void leaving() {
        refresh(clock.millis(), Map.of());
    }

    /** @param partition 按生产者键计算的提交分区
     * @return 仍可接纳新预约时为 true
     */
    boolean allows(int partition) {
        long now = clock.millis();
        var view = durable;
        if (!fresh(view.observedAt(), now) || view.partitions().contains(partition)) return false;
        var observation = lastReady.get(partition);
        if (observation == null || !fresh(observation.observedAt(), now)) return false;
        Snapshot owner = members.get(observation.member());
        boolean current = owner != null && owner.bootId().equals(observation.bootId())
                && owner.partitions().containsKey(partition) && fresh(owner.observedAt(), now);
        // 失去持有者时以最近一次实际采样为准，重读同一个快照不能续延宽限。
        if (!current && now - observation.observedAt() > instance.handoff().toMillis()) return false;
        Lag lag = observation.lag();
        return lag.known() && (lag.oldestTimestamp() == null
                || now - lag.oldestTimestamp() < instance.backlogLimit().toMillis());
    }

    /** @param partition 目标提交分区
     * @throws RoomMessageFailure 目标分区暂不可处理，须在建立预约前返回
     */
    void require(int partition) {
        if (!allows(partition)) throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
    }

    private boolean fresh(long sampledAt, long now) {
        // 只容忍有限的主机时钟差，远未来快照不得长时间保有资格。
        return sampledAt > 0 && sampledAt <= now + 1_000 && now - sampledAt < instance.freshness().toMillis();
    }

    /** @param known 是否确知积压状态
     * @param oldestTimestamp 最老待处理记录时间，已确认无积压时为空
     */
    record Lag(boolean known, Long oldestTimestamp) {}
    record Snapshot(String bootId, long observedAt, Map<Integer, Lag> partitions) {}
    private record Observation(String member, String bootId, long observedAt, Lag lag) {}
    private record BlockView(long observedAt, Set<Integer> partitions) {}
}
