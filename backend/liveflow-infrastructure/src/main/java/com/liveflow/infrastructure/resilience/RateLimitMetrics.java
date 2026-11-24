package com.liveflow.infrastructure.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 按固定策略和裁决结果记录限流调用，避免将请求身份带入标签。 */
@Component
public class RateLimitMetrics {

    public enum Policy { LOGIN, REGISTRATION, ROOM_MESSAGES }
    public enum Outcome { ALLOWED, LIMITED, UNAVAILABLE }

    private final MeterRegistry registry;

    /** @param registry 应用指标仓储 */
    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 计时器的 count 即对应策略和结果的请求数。
     * @param policy 限流策略
     * @param outcome 限流裁决结果
     * @param started 进入 Redis 前的单调时钟读数
     */
    public void record(Policy policy, Outcome outcome, long started) {
        Timer.builder("liveflow.rate.limit")
                .tags("policy", policy.name().toLowerCase(Locale.ROOT),
                        "outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
}
