package com.liveflow.infrastructure.room;

import com.liveflow.core.room.mapper.RoomMapper;
import com.liveflow.core.room.service.RoomExistenceStore.Unavailable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomExistenceRepositoryTest {
    @Test
    void limitsBurstAndReplenishesOnlyWithElapsedTime() {
        var clock = new AtomicLong();
        var budget = new RoomSourceBudget(30, 2, 1, clock::get);
        assertThat(budget.tryStart()).isEqualTo(RoomSourceBudget.Decision.ADMITTED);
        assertThat(budget.tryStart()).isEqualTo(RoomSourceBudget.Decision.CONCURRENCY_LIMIT);
        budget.finish();
        assertThat(budget.tryStart()).isEqualTo(RoomSourceBudget.Decision.ADMITTED);
        budget.finish();
        assertThat(budget.tryStart()).isEqualTo(RoomSourceBudget.Decision.RATE_LIMIT);
        clock.set(Duration.ofMillis(34).toNanos());
        assertThat(budget.tryStart()).isEqualTo(RoomSourceBudget.Decision.ADMITTED);
        budget.finish();
        assertThat(budget.tryStart()).isEqualTo(RoomSourceBudget.Decision.RATE_LIMIT);
        assertThat(budget.inFlight()).isZero();
    }

    @Test
    void timedOutFollowerLeavesTheQueryAndItsPermitInFlight() throws Exception {
        String roomId = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        var mapper = mock(RoomMapper.class);
        var metrics = new SimpleMeterRegistry();
        var repository = new RoomExistenceRepository(mapper, settings(false, 1), metrics, "test");
        var sent = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(mapper.exists(roomId)).thenAnswer(call -> {
            sent.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return true;
        });

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var leader = workers.submit(() -> repository.exists(roomId));
            try {
                assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> repository.exists(roomId)).isInstanceOf(Unavailable.class);
                assertThat(metrics.get("liveflow.rooms.existence.source.inflight").gauge().value()).isEqualTo(1);
                assertThatThrownBy(() -> repository.exists(other)).isInstanceOf(Unavailable.class);
            } finally {
                finish.countDown();
            }
            assertThat(leader.get(2, TimeUnit.SECONDS)).isTrue();
        }
        verify(mapper).exists(roomId);
        assertThat(metrics.get("liveflow.rooms.existence.source.inflight").gauge().value()).isZero();
        assertThat(repository.exists(other)).isFalse();
        assertThat(metrics.get("liveflow.rooms.existence").tags("instance", "test", "operation", "source",
                "result", "concurrency_limit").counter().count()).isEqualTo(1);
    }

    @Test
    void sourceFailurePreservesErrorAndLeavesTheNextLookupAvailable() {
        String roomId = UUID.randomUUID().toString();
        var mapper = mock(RoomMapper.class);
        var repository = new RoomExistenceRepository(mapper, settings(false, 1), new SimpleMeterRegistry(), "test");
        var unavailable = new DataAccessResourceFailureException("test");
        when(mapper.exists(roomId)).thenThrow(unavailable).thenReturn(true);

        assertThatThrownBy(() -> repository.exists(roomId)).isSameAs(unavailable);
        assertThat(repository.exists(roomId)).isTrue();
    }

    @Test
    void cacheConnectionFailureStillUsesTheSameSourceBudget() {
        String roomId = UUID.randomUUID().toString();
        var mapper = mock(RoomMapper.class);
        var metrics = new SimpleMeterRegistry();
        var repository = new RoomExistenceRepository(mapper, settings(true, 1), metrics, "test");
        try {
            when(mapper.exists(roomId)).thenReturn(true);
            assertThat(repository.exists(roomId)).isTrue();
            assertThat(metrics.get("liveflow.rooms.existence").tags("instance", "test", "operation", "lookup",
                    "result", "cache_error").counter().count()).isEqualTo(1);
            verify(mapper).exists(roomId);
        } finally {
            repository.closeCache();
        }
    }

    @Test
    void rejectsInvalidTtlAndTimeoutOrdering() {
        var settings = settings(false, 1);
        assertThatThrownBy(() -> new RoomCacheSettings.Ttl(Duration.ofSeconds(6), Duration.ofSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoomCacheSettings(false, settings.environment(), settings.redis(),
                settings.positiveTtl(), settings.negativeTtl(), settings.fillLease(),
                settings.redis().commandTimeout(), 30, 6, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoomCacheSettings(false, settings.environment(), settings.redis(),
                settings.positiveTtl(), settings.negativeTtl(), settings.fillLease(),
                settings.waitTimeout(), 30, 0, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RoomCacheSettings settings(boolean enabled, int concurrency) {
        return new RoomCacheSettings(enabled, "test",
                new RoomCacheSettings.Redis("redis://127.0.0.1:1", Duration.ofMillis(100), Duration.ofMillis(20)),
                new RoomCacheSettings.Ttl(Duration.ofMinutes(4), Duration.ofMinutes(6)),
                new RoomCacheSettings.Ttl(Duration.ofSeconds(4), Duration.ofSeconds(6)),
                Duration.ofSeconds(5), Duration.ofMillis(45), 30, 6, concurrency);
    }
}
