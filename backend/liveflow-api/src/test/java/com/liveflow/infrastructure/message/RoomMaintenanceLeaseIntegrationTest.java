package com.liveflow.infrastructure.message;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用隔离 Redis 验证真实 Lua 的租约归属与过期接管。 */
@EnabledIfEnvironmentVariable(named = "LIVEFLOW_AUTH_INTEGRATION", matches = "true")
class RoomMaintenanceLeaseIntegrationTest {
    @Test
    void expiredOwnerCannotCompleteOrReleaseItsSuccessor() throws Exception {
        var redisConfig = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("LIVEFLOW_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("LIVEFLOW_REDIS_PORT", "16379")));
        redisConfig.setDatabase(14);
        var factory = new LettuceConnectionFactory(redisConfig);
        factory.afterPropertiesSet();
        try {
            var redis = new StringRedisTemplate(factory);
            String scope = "lease-it-" + UUID.randomUUID();
            var topology = new RoomMessageTopology("verify");
            var first = new RoomMaintenanceLease(redis, topology, scope);
            var second = new RoomMaintenanceLease(redis, topology, scope);
            String leaseKey = scope + ":liveflow:rooms:message:maintenance:verify:receipt-rebuild";
            String marker = scope + ":marker";
            try (var original = first.acquire("receipt-rebuild").orElseThrow()) {
                assertThat(second.acquire("receipt-rebuild")).isEmpty();
                // 提前设置租约过期，保留真实 Redis 的删除及接管次序。
                redis.expire(leaseKey, Duration.ofMillis(1));
                Thread.sleep(15);
                try (var successor = second.acquire("receipt-rebuild").orElseThrow()) {
                    assertThat(original.renew()).isFalse();
                    assertThat(original.complete(marker, Duration.ofSeconds(1))).isFalse();
                    assertThat(redis.hasKey(marker)).isFalse();
                    original.close();
                    assertThat(successor.renew()).isTrue();
                    assertThat(successor.complete(marker, Duration.ofSeconds(1))).isTrue();
                    assertThat(redis.opsForValue().get(marker)).isEqualTo("1");
                }
            } finally {
                redis.delete(marker);
            }
        } finally {
            factory.destroy();
        }
    }
}
