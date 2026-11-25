package com.liveflow.infrastructure.message;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 用互相独立的环境级 Redis 租约协调回执重建与过期历史清理。 */
@Component
final class RoomMaintenanceLease {
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final RedisScript<Long> RENEW = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            """, Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);
    private static final RedisScript<Long> COMPLETE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('PSETEX', KEYS[2], ARGV[2], '1')
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    /** @param redis 共享 Redis 命令客户端
     * @param topology Kafka 命名空间
     * @param environment Redis 部署环境
     */
    RoomMaintenanceLease(StringRedisTemplate redis, RoomMessageTopology topology,
            @Value("${liveflow.room-messages.v2.environment:local}") String environment) {
        this.redis = redis;
        prefix = environment + ":liveflow:rooms:message:maintenance:" + topology.namespace() + ":";
    }

    /** @param task 作业名
     * @return 本次执行取得的租约，已有持有者时为空
     */
    Optional<Lease> acquire(String task) {
        String key = prefix + task;
        String token = UUID.randomUUID().toString();
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, TTL))) {
            return Optional.of(new Lease(key, token));
        }
        return Optional.empty();
    }

    /** 单次作业的所有权。过期的执行不能续租、完成或释放继任者的租约。 */
    final class Lease implements AutoCloseable {
        private final String key;
        private final String token;

        private Lease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        /** @return 成功确认所有权并延长租期 */
        boolean renew() {
            return Long.valueOf(1).equals(redis.execute(RENEW, List.of(key), token, Long.toString(TTL.toMillis())));
        }

        /** @param marker 已完成回执恢复的标记
         * @param ttl 标记保留时间
         * @return 仍持有租约且已写入标记
         */
        boolean complete(String marker, Duration ttl) {
            return Long.valueOf(1).equals(redis.execute(COMPLETE, List.of(key, marker),
                    token, Long.toString(ttl.toMillis())));
        }

        /** 释放本次租约。已丢失所有权时无需操作。 */
        @Override
        public void close() {
            redis.execute(RELEASE, List.of(key), token);
        }
    }
}
