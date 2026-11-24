package com.liveflow.infrastructure.resilience;

import com.liveflow.core.resilience.RoomMessageCircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 在业务发送入口管理 Resilience4j 许可，并隔离不同轮次的完成结果。 */
@Component
@EnableConfigurationProperties(RoomMessageCircuitSettings.class)
public final class Resilience4jRoomMessageCircuitBreaker implements RoomMessageCircuitBreaker {
    private static final String METRIC = "liveflow.room.messages.circuit";

    private final Object lock = new Object();
    private final CircuitBreaker breaker;
    private final MeterRegistry metrics;
    private final LongSupplier nanoTime;
    private final long slowCallNanos;
    private long generation;

    /**
     * @param settings 发送熔断策略和初始化模式
     * @param metrics 指标仓储
     */
    @Autowired
    public Resilience4jRoomMessageCircuitBreaker(RoomMessageCircuitSettings settings, MeterRegistry metrics) {
        this(settings, metrics, Clock.systemUTC(), System::nanoTime);
    }

    Resilience4jRoomMessageCircuitBreaker(RoomMessageCircuitSettings settings, MeterRegistry metrics,
            Clock clock, LongSupplier nanoTime) {
        this.metrics = metrics;
        this.nanoTime = nanoTime;
        this.slowCallNanos = settings.slowCallDurationThreshold().toNanos();
        var initialState = settings.mode() == RoomMessageCircuitSettings.Mode.OBSERVE ? State.METRICS_ONLY : State.CLOSED;
        var config = CircuitBreakerConfig.custom()
                .clock(clock)
                .initialState(initialState)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(settings.slidingWindowSeconds())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .slowCallDurationThreshold(settings.slowCallDurationThreshold())
                .slowCallRateThreshold(settings.slowCallRateThreshold())
                .waitIntervalFunctionInOpenState(IntervalFunction.ofExponentialBackoff(
                        settings.openInitialWait(), 2, settings.openMaxWait()))
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenPermittedCalls())
                .maxWaitDurationInHalfOpenState(settings.halfOpenMaxWait())
                .transitionToStateAfterWaitDuration(State.OPEN)
                .build();
        this.breaker = CircuitBreaker.of("room-message-send", config);
        breaker.getEventPublisher().onStateTransition(event -> {
            var transition = event.getStateTransition();
            // 进入半开和恢复正常由持锁的准入或回报触发，库的定时器只会打开断路器。
            if (transition.getToState() == State.HALF_OPEN || transition.getToState() == State.CLOSED) {
                generation++;
            }
            metrics.counter(METRIC + ".transitions", "from", label(transition.getFromState()),
                    "to", label(transition.getToState())).increment();
        });
        for (State state : State.values()) {
            Gauge.builder(METRIC + ".state", breaker, circuit -> circuit.getState() == state ? 1 : 0)
                    .tags("mode", label(settings.mode()), "state", label(state)).register(metrics);
        }
        Gauge.builder(METRIC + ".slow.rate", breaker, circuit -> circuit.getMetrics().getSlowCallRate())
                .register(metrics);
        Gauge.builder(METRIC + ".failure.rate", breaker, circuit -> circuit.getMetrics().getFailureRate())
                .register(metrics);
    }

    @Override
    public Optional<Permit> tryAcquire() {
        State state;
        synchronized (lock) {
            boolean permitted = breaker.tryAcquirePermission();
            // 首个等待期后的请求可能在取许可时触发半开，随后读取新的状态及轮次。
            state = breaker.getState();
            if (permitted && state != State.OPEN) {
                return Optional.of(new Attempt(generation, state, nanoTime.getAsLong()));
            }
        }
        metrics.counter(METRIC + ".rejected", "state", label(state)).increment();
        return Optional.empty();
    }

    private enum Outcome { SUCCESS, FAILURE, RELEASED }

    private final class Attempt implements Permit {
        private final long acquiredGeneration;
        private final State acquiredState;
        private final long started;
        private boolean completed;

        private Attempt(long acquiredGeneration, State acquiredState, long started) {
            this.acquiredGeneration = acquiredGeneration;
            this.acquiredState = acquiredState;
            this.started = started;
        }

        @Override
        public void succeeded() {
            settle(Outcome.SUCCESS, null, null);
        }

        @Override
        public void failed(Dependency dependency, Throwable failure) {
            settle(Outcome.FAILURE, dependency, failure);
        }

        @Override
        public void release() {
            settle(Outcome.RELEASED, null, null);
        }

        private void settle(Outcome outcome, Dependency dependency, Throwable failure) {
            long elapsed = nanoTime.getAsLong() - started;
            boolean current;
            synchronized (lock) {
                if (completed) return;
                completed = true;
                // 旧回调仍可影响原提交，但不能写入新样本或归还新一轮的许可。
                current = acquiredGeneration == generation && acquiredState == breaker.getState();
                if (current) {
                    switch (outcome) {
                        case SUCCESS -> breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                        case FAILURE -> breaker.onError(elapsed, TimeUnit.NANOSECONDS, failure);
                        case RELEASED -> breaker.releasePermission();
                    }
                }
            }
            String stage = dependency == null ? "none" : label(dependency);
            metrics.counter(METRIC + ".outcomes", "state", label(acquiredState),
                    "outcome", label(outcome), "dependency", stage, "current", Boolean.toString(current)).increment();
            if (outcome != Outcome.RELEASED) {
                metrics.timer(METRIC + ".duration", "outcome", label(outcome), "dependency", stage,
                        "slow", Boolean.toString(elapsed > slowCallNanos)).record(elapsed, TimeUnit.NANOSECONDS);
            }
        }
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
