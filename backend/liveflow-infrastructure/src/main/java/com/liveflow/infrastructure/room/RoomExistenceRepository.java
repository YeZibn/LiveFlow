package com.liveflow.infrastructure.room;

import com.liveflow.core.room.mapper.RoomMapper;
import com.liveflow.core.room.service.RoomExistenceStore;
import com.liveflow.infrastructure.room.RoomCacheRedis.CachedResult;
import com.liveflow.infrastructure.room.RoomCacheRedis.Failure;
import com.liveflow.infrastructure.room.RoomCacheRedis.Fill;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Redis 不可用时仍可按实例配额向 MySQL 确认房间存在性。 */
@Component
@EnableConfigurationProperties(RoomCacheSettings.class)
final class RoomExistenceRepository implements RoomExistenceStore {
    private static final Logger LOG = LoggerFactory.getLogger(RoomExistenceRepository.class);
    private final RoomMapper rooms;
    private final RoomCacheSettings settings;
    private final RoomCacheRedis cache;
    private final RoomSourceBudget budget;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> localQueries = new ConcurrentHashMap<>();
    private final EnumMap<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
    private final Timer sourceLatency;

    /** @param rooms 房间存在性源查询
     * @param settings 独立缓存和回源预算
     * @param metrics 应用指标
     * @param instance 本机的部署标识
     */
    RoomExistenceRepository(RoomMapper rooms, RoomCacheSettings settings, MeterRegistry metrics,
            @Value("${liveflow.instance-id:local}") String instance) {
        this.rooms = rooms;
        this.settings = settings;
        cache = settings.enabled() ? new RoomCacheRedis(settings) : null;
        budget = new RoomSourceBudget(settings.sourceRate(), settings.sourceBurst(), settings.sourceConcurrency(),
                System::nanoTime);
        for (var outcome : Outcome.values()) {
            outcomes.put(outcome, metrics.counter("liveflow.rooms.existence", "instance", instance,
                    "operation", outcome.operation, "result", outcome.result));
        }
        sourceLatency = metrics.timer("liveflow.rooms.existence.source.duration", "instance", instance);
        Gauge.builder("liveflow.rooms.existence.source.inflight", budget, RoomSourceBudget::inFlight)
                .tag("instance", instance).register(metrics);
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(String roomId) {
        if (Thread.currentThread().isInterrupted()) throw new Unavailable();
        boolean useCache = cache != null;
        if (useCache) {
            try {
                // 先读已发布的数据。创建后的正缓存无需等待同机尚未结束的旧回填。
                CachedResult value = cache.read(roomId, settings.redis().commandTimeout());
                if (value != CachedResult.MISS) return hit(value);
                metric(Outcome.MISS);
            } catch (Failure failed) {
                metric(Outcome.CACHE_ERROR);
                useCache = false;
            }
        }
        return loadShared(roomId, useCache);
    }

    private boolean loadShared(String roomId, boolean useCache) {
        var own = new CompletableFuture<Boolean>();
        var running = localQueries.putIfAbsent(roomId, own);
        if (running != null) return awaitLocal(running);
        try {
            boolean exists = useCache ? fillOrWait(roomId) : fromSource(roomId);
            own.complete(exists);
            return exists;
        } catch (RuntimeException | Error failed) {
            own.completeExceptionally(failed);
            throw failed;
        } finally {
            // 仅保留在途工作。下一个请求重新读取缓存，有机会取得创建后的新结果。
            localQueries.remove(roomId, own);
        }
    }

    private boolean fillOrWait(String roomId) {
        Fill fill;
        try {
            fill = cache.acquire(roomId);
        } catch (Failure failed) {
            metric(Outcome.CACHE_ERROR);
            return fromSource(roomId);
        }
        if (fill.value() != CachedResult.MISS) return hit(fill.value());
        if (fill.token() == null) return awaitFill(roomId);
        metric(Outcome.ACQUIRED);
        return fill(roomId, fill);
    }

    /** {@inheritDoc} */
    @Override
    public void warmCreated(String roomId) {
        if (cache == null) return;
        try {
            cache.warmCreated(roomId);
        } catch (Failure failed) {
            metric(Outcome.WARM_ERROR);
            LOG.warn("已提交房间缓存预热失败 roomId={}", roomId);
        }
    }

    private boolean fill(String roomId, Fill fill) {
        boolean exists;
        try {
            exists = fromSource(roomId);
        } catch (RuntimeException | Error failed) {
            try {
                cache.release(roomId, fill);
            } catch (Failure releaseFailed) {
                metric(Outcome.FILL_ERROR);
            }
            throw failed;
        }
        try {
            if (!cache.finish(roomId, fill, exists)) metric(Outcome.LOST_LEASE);
        } catch (Failure failed) {
            // 回填失败只影响后续缓存命中，本次仍使用已经确认的 MySQL 结果。
            metric(Outcome.FILL_ERROR);
        }
        return exists;
    }

    private boolean awaitFill(String roomId) {
        long deadline = System.nanoTime() + settings.waitTimeout().toNanos();
        try {
            while (deadline - System.nanoTime() > 0) {
                long delay = TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(20, 41));
                TimeUnit.NANOSECONDS.sleep(Math.min(delay, Math.max(0, deadline - System.nanoTime())));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                CachedResult value = cache.read(roomId, Duration.ofNanos(remaining));
                if (value != CachedResult.MISS) {
                    metric(Outcome.SHARED_RESULT);
                    return hit(value);
                }
            }
        } catch (Failure failed) {
            if (deadline - System.nanoTime() > 0) {
                metric(Outcome.WAIT_ERROR);
                throw new Unavailable();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        }
        // 已看到共享持有者的等待请求结束。即使 Redis 中途失败也不各自回源。
        metric(Outcome.WAIT_TIMEOUT);
        throw new Unavailable();
    }

    private boolean awaitLocal(CompletableFuture<Boolean> running) {
        try {
            boolean exists = running.get(settings.waitTimeout().toNanos(), TimeUnit.NANOSECONDS);
            metric(Outcome.LOCAL_RESULT);
            return exists;
        } catch (TimeoutException timeout) {
            metric(Outcome.WAIT_TIMEOUT);
            throw new Unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            if (failed.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("房间回源返回了未预期的异常", failed.getCause());
        }
    }

    private boolean fromSource(String roomId) {
        if (Thread.currentThread().isInterrupted()) throw new Unavailable();
        switch (budget.tryStart()) {
            case RATE_LIMIT -> {
                metric(Outcome.RATE_LIMIT);
                throw new Unavailable();
            }
            case CONCURRENCY_LIMIT -> {
                metric(Outcome.CONCURRENCY_LIMIT);
                throw new Unavailable();
            }
            case ADMITTED -> metric(Outcome.SOURCE_STARTED);
        }
        long started = System.nanoTime();
        try {
            return rooms.exists(roomId);
        } catch (RuntimeException failed) {
            metric(Outcome.SOURCE_ERROR);
            throw failed;
        } finally {
            // JDBC 真正返回或抛出异常后才归还额度，等待方提前离开不释放执行方的额度。
            budget.finish();
            sourceLatency.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private boolean hit(CachedResult value) {
        boolean exists = value == CachedResult.PRESENT;
        metric(exists ? Outcome.POSITIVE_HIT : Outcome.NEGATIVE_HIT);
        return exists;
    }

    private void metric(Outcome outcome) {
        outcomes.get(outcome).increment();
    }

    @PreDestroy
    void closeCache() {
        if (cache != null) cache.close();
    }

    private enum Outcome {
        POSITIVE_HIT("lookup", "positive_hit"),
        NEGATIVE_HIT("lookup", "negative_hit"),
        MISS("lookup", "miss"),
        CACHE_ERROR("lookup", "cache_error"),
        ACQUIRED("fill", "owner"),
        SHARED_RESULT("fill", "shared_result"),
        LOCAL_RESULT("fill", "local_result"),
        WAIT_TIMEOUT("fill", "wait_timeout"),
        WAIT_ERROR("fill", "wait_error"),
        LOST_LEASE("fill", "lost_lease"),
        FILL_ERROR("fill", "error"),
        WARM_ERROR("warm", "error"),
        SOURCE_STARTED("source", "started"),
        SOURCE_ERROR("source", "error"),
        RATE_LIMIT("source", "rate_limit"),
        CONCURRENCY_LIMIT("source", "concurrency_limit");

        private final String operation;
        private final String result;

        Outcome(String operation, String result) {
            this.operation = operation;
            this.result = result;
        }
    }
}
