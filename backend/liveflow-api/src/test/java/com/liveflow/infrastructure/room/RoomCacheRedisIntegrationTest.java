package com.liveflow.infrastructure.room;

import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static com.liveflow.infrastructure.room.RoomCacheRedis.CachedResult.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "LIVEFLOW_ROOM_CACHE_TEST_PORT", matches = "[0-9]+")
class RoomCacheRedisIntegrationTest {
    @Test
    void sharesConfirmedAbsenceWithoutSlidingItsDeadlineAndRejectsInvalidValues() {
        String room = UUID.randomUUID().toString();
        var settings = settings(Duration.ofSeconds(1), Duration.ofSeconds(2));
        try (var one = new RoomCacheRedis(settings); var two = new RoomCacheRedis(settings);
                var client = RedisClient.create(settings.redis().uri()); var inspect = client.connect()) {
            var fill = one.acquire(room);
            assertThat(fill.token()).isNotNull();
            assertThat(two.acquire(room).token()).isNull();
            assertThat(one.finish(room, fill, false)).isTrue();
            assertThat(two.read(room, settings.waitTimeout())).isEqualTo(ABSENT);
            String key = key(settings, room, "value");
            assertThat(inspect.sync().pexpiretime(key)).isEqualTo(fill.negativeDeadlineMs());
            assertThat(two.read(room, settings.waitTimeout())).isEqualTo(ABSENT);
            assertThat(inspect.sync().pexpiretime(key)).isEqualTo(fill.negativeDeadlineMs());
            inspect.sync().psetex(key, 2000, "unexpected");
            assertThatThrownBy(() -> two.read(room, settings.waitTimeout()))
                    .isInstanceOf(RoomCacheRedis.Failure.class);
        }
    }

    @Test
    void prewarmingFencesLateAbsenceAndCrossNodeReadsSeePresent() {
        String room = UUID.randomUUID().toString();
        var settings = settings(Duration.ofSeconds(1), Duration.ofSeconds(2));
        try (var one = new RoomCacheRedis(settings); var two = new RoomCacheRedis(settings)) {
            var stale = one.acquire(room);
            assertThat(stale.token()).isNotNull();
            two.warmCreated(room);
            assertThat(one.finish(room, stale, false)).isFalse();
            assertThat(one.read(room, settings.waitTimeout())).isEqualTo(PRESENT);
        }
    }

    @Test
    void expiredLeaseCannotFreeItsSuccessorAndLateNegativeIsDiscarded() throws Exception {
        String room = UUID.randomUUID().toString();
        var settings = settings(Duration.ofMillis(30), Duration.ofMillis(400));
        try (var one = new RoomCacheRedis(settings); var two = new RoomCacheRedis(settings);
                var client = RedisClient.create(settings.redis().uri()); var inspect = client.connect()) {
            var stale = one.acquire(room);
            assertThat(stale.token()).isNotNull();
            // Advance expiry for the first lease only so the test does not depend on its full wall-clock interval.
            inspect.sync().pexpireat(key(settings, room, "fill"), 1);
            var successor = two.acquire(room);
            assertThat(successor.token()).isNotNull().isNotEqualTo(stale.token());
            one.release(room, stale);
            assertThat(inspect.sync().get(key(settings, room, "fill"))).isEqualTo(successor.token());
            assertThat(one.finish(room, stale, false)).isFalse();
            TimeUnit.MILLISECONDS.sleep(45);
            assertThat(two.finish(room, successor, false)).isTrue();
            assertThat(one.read(room, settings.waitTimeout())).isEqualTo(MISS);
            assertThat(inspect.sync().get(key(settings, room, "fill"))).isNull();
        }
    }

    @Test
    void unconfirmedCacheWriteReportsFailureWithinTheCommandBudget() {
        String room = UUID.randomUUID().toString();
        var settings = settings(Duration.ofSeconds(1), Duration.ofSeconds(2));
        try (var cache = new RoomCacheRedis(settings);
                var client = RedisClient.create(settings.redis().uri()); var inspect = client.connect()) {
            assertThat(cache.read(room, settings.waitTimeout())).isEqualTo(MISS);
            // 只在隔离缓存 Redis 上暂停，以制造命令已发送但尚未确认的情况。
            assertThat(inspect.sync().clientPause(500)).isEqualTo("OK");
            try {
                assertThatThrownBy(() -> cache.warmCreated(room)).isInstanceOf(RoomCacheRedis.Failure.class);
            } finally {
                assertThat(inspect.sync().ping()).isEqualTo("PONG");
            }
        }
    }

    private static RoomCacheSettings settings(Duration negativeTtl, Duration lease) {
        String port = System.getenv("LIVEFLOW_ROOM_CACHE_TEST_PORT");
        return new RoomCacheSettings(true, "room-cache-it-" + UUID.randomUUID(),
                new RoomCacheSettings.Redis("redis://127.0.0.1:" + port, Duration.ofMillis(250), Duration.ofMillis(150)),
                new RoomCacheSettings.Ttl(Duration.ofSeconds(4), Duration.ofSeconds(6)),
                new RoomCacheSettings.Ttl(negativeTtl, negativeTtl), lease, Duration.ofMillis(200), 30, 6, 4);
    }

    private static String key(RoomCacheSettings settings, String roomId, String suffix) {
        return settings.environment() + ":liveflow:room:exists:v1:{" + roomId + "}:" + suffix;
    }
}
