package com.liveflow.infrastructure.resilience;

import com.liveflow.core.resilience.RoomMessageCircuitBreaker.Dependency;
import com.liveflow.core.resilience.RoomMessageCircuitBreaker.Permit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static com.liveflow.infrastructure.resilience.RoomMessageCircuitSettings.Mode.ENFORCE;
import static com.liveflow.infrastructure.resilience.RoomMessageCircuitSettings.Mode.OBSERVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RoomMessageCircuitBreakerTest {
    private static final String METRIC = "liveflow.room.messages.circuit";
    private static final RuntimeException FAILURE = new IllegalStateException("dependency unavailable");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ManualClock clock = new ManualClock();
    private final AtomicLong nanos = new AtomicLong();

    @Test
    void thresholdsRequireThirtyRecentCompletionsAndIncludeExactlyTwentyPercentFailures() {
        var breaker = circuit(ENFORCE, Duration.ofSeconds(60));
        IntStream.range(0, 29).forEach(ignored -> fail(acquire(breaker)));
        assertState("closed");
        clock.advance(Duration.ofSeconds(11));
        // 旧窗口中的 29 次失败已经不参与近 10 秒的统计。
        IntStream.range(0, 24).forEach(ignored -> acquire(breaker).succeeded());
        IntStream.range(0, 5).forEach(ignored -> fail(acquire(breaker)));
        assertState("closed");
        fail(acquire(breaker));
        assertState("open");
        assertThat(breaker.tryAcquire()).isEmpty();
    }

    @Test
    void exactlyEightHundredMillisIsNotSlowButFortyPercentAboveItOpens() {
        var breaker = circuit(ENFORCE, Duration.ofSeconds(60));
        IntStream.range(0, 18).forEach(ignored -> confirmAfter(breaker, 800));
        IntStream.range(0, 11).forEach(ignored -> confirmAfter(breaker, 801));
        assertState("closed");
        confirmAfter(breaker, 801);
        assertState("open");
    }

    @Test
    void observeModeReportsFaultsAndContinuesAdmitting() {
        var breaker = circuit(OBSERVE, Duration.ofSeconds(60));
        IntStream.range(0, 30).forEach(ignored -> fail(acquire(breaker)));
        assertState("metrics_only");
        assertThat(meters.get(METRIC + ".failure.rate").gauge().value()).isEqualTo(100);
        acquire(breaker).release();
    }

    @Test
    void returnsOneHalfOpenPermitAndRejectsLateClosedResults() throws Exception {
        var breaker = circuit(ENFORCE, Duration.ofSeconds(60));
        var staleSuccess = acquire(breaker);
        var staleFailure = acquire(breaker);
        var staleRelease = acquire(breaker);
        open(breaker);
        clock.advance(Duration.ofSeconds(5).plusMillis(1));
        // 同一时刻最多只有五个请求拿到许可，其余请求直接拒绝。
        List<Permit> probes;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Permit>> calls = IntStream.range(0, 12)
                    .mapToObj(ignored -> (Callable<Permit>) () -> breaker.tryAcquire().orElse(null)).toList();
            probes = executor.invokeAll(calls).stream().filter(future -> future.resultNow() != null)
                    .map(Future::resultNow).toList();
        }
        assertThat(probes).hasSize(5);
        staleRelease.release();
        staleSuccess.succeeded();
        staleFailure.failed(Dependency.MYSQL, FAILURE);
        assertThat(breaker.tryAcquire()).isEmpty();

        var ignored = probes.getFirst();
        ignored.release();
        ignored.release();
        var replacement = acquire(breaker);
        assertThat(breaker.tryAcquire()).isEmpty();
        probes.subList(1, 5).forEach(Permit::succeeded);
        assertState("half_open");
        replacement.succeeded();
        assertState("closed");
    }

    @Test
    void failedAndSlowProbesBackOffThenHealthyProbesResetTheDelay() {
        var breaker = circuit(ENFORCE, Duration.ofSeconds(60));
        open(breaker);
        reachHalfOpen(breaker, 5).failed(Dependency.KAFKA, FAILURE);
        IntStream.range(0, 4).forEach(ignored -> acquire(breaker).succeeded());
        assertState("open");

        confirmAfter(reachHalfOpen(breaker, 10), 801);
        confirmAfter(breaker, 801);
        IntStream.range(0, 3).forEach(ignored -> acquire(breaker).succeeded());
        assertState("open");
        fail(reachHalfOpen(breaker, 20));
        IntStream.range(0, 4).forEach(ignored -> acquire(breaker).succeeded());
        assertState("open");
        fail(reachHalfOpen(breaker, 30));
        IntStream.range(0, 4).forEach(ignored -> acquire(breaker).succeeded());
        assertState("open");

        confirmAfter(reachHalfOpen(breaker, 30), 801);
        IntStream.range(0, 4).forEach(ignored -> acquire(breaker).succeeded());
        assertState("closed");
        open(breaker);
        reachHalfOpen(breaker, 5).release();
    }

    @Test
    void halfOpenExpiresAndPreviousHalfOpenCallbackIsStaleEvenInTheSameState() {
        // 生产配置为 60 秒，测试缩短库的调度等待并用指标观察超时。
        var breaker = circuit(ENFORCE, Duration.ofMillis(200));
        open(breaker);
        var stale = reachHalfOpen(breaker, 5);
        await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertState("open"));
        reachHalfOpen(breaker, 10).succeeded();
        stale.succeeded();
        IntStream.range(0, 3).forEach(ignored -> acquire(breaker).succeeded());
        assertState("half_open");
        acquire(breaker).succeeded();
        assertState("closed");
    }

    private Resilience4jRoomMessageCircuitBreaker circuit(RoomMessageCircuitSettings.Mode mode, Duration halfOpenWait) {
        var settings = new RoomMessageCircuitSettings(mode, 10, 30, 20, Duration.ofMillis(800), 40,
                Duration.ofSeconds(5), Duration.ofSeconds(30), 5, halfOpenWait);
        return new Resilience4jRoomMessageCircuitBreaker(settings, meters, clock, nanos::get);
    }

    private void open(Resilience4jRoomMessageCircuitBreaker breaker) {
        IntStream.range(0, 30).forEach(ignored -> fail(acquire(breaker)));
        assertState("open");
    }

    private Permit reachHalfOpen(Resilience4jRoomMessageCircuitBreaker breaker, int seconds) {
        clock.advance(Duration.ofSeconds(seconds).minusMillis(1));
        assertThat(breaker.tryAcquire()).isEmpty();
        clock.advance(Duration.ofMillis(2));
        var permit = acquire(breaker);
        assertState("half_open");
        return permit;
    }

    private static Permit acquire(Resilience4jRoomMessageCircuitBreaker breaker) {
        return breaker.tryAcquire().orElseThrow();
    }

    private static void fail(Permit permit) {
        permit.failed(Dependency.KAFKA, FAILURE);
    }

    private void confirmAfter(Resilience4jRoomMessageCircuitBreaker breaker, long millis) {
        confirmAfter(acquire(breaker), millis);
    }

    private void confirmAfter(Permit permit, long millis) {
        nanos.addAndGet(Duration.ofMillis(millis).toNanos());
        permit.succeeded();
    }

    private void assertState(String state) {
        assertThat(meters.get(METRIC + ".state").tag("state", state).gauge().value()).isEqualTo(1);
    }

    private static final class ManualClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-24T00:00:00Z");

        void advance(Duration elapsed) { now = now.plus(elapsed); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) throw new IllegalArgumentException("测试时钟只使用 UTC");
            return this;
        }
    }
}
