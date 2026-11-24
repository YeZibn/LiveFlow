package com.liveflow.infrastructure.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param mode 只观测或执行拒绝
 * @param slidingWindowSeconds 正常状态的时间窗口秒数
 * @param minimumNumberOfCalls 正常状态计算比率所需的最少有效样本
 * @param failureRateThreshold 打开断路器的失败百分比
 * @param slowCallDurationThreshold 超过该时长的有效结果计为慢调用
 * @param slowCallRateThreshold 打开断路器的慢调用百分比
 * @param openInitialWait 每轮故障首次暂停的等待时间
 * @param openMaxWait 连续打开的等待上限
 * @param halfOpenPermittedCalls 半开所需的有效结果数与同时在途上限
 * @param halfOpenMaxWait 从进入半开起等待有效结果的上限
 */
@ConfigurationProperties("liveflow.resilience.circuit-breaker.room-messages")
public record RoomMessageCircuitSettings(
        @DefaultValue("observe") Mode mode,
        @DefaultValue("10") int slidingWindowSeconds,
        @DefaultValue("30") int minimumNumberOfCalls,
        @DefaultValue("20") float failureRateThreshold,
        @DefaultValue("800ms") Duration slowCallDurationThreshold,
        @DefaultValue("40") float slowCallRateThreshold,
        @DefaultValue("5s") Duration openInitialWait,
        @DefaultValue("30s") Duration openMaxWait,
        @DefaultValue("5") int halfOpenPermittedCalls,
        @DefaultValue("60s") Duration halfOpenMaxWait) {

    /** 配置中的运行模式。 */
    public enum Mode { OBSERVE, ENFORCE }

    /** @throws IllegalArgumentException 样本数、比例或时长超出断路器支持范围 */
    public RoomMessageCircuitSettings {
        if (slidingWindowSeconds < 1 || halfOpenPermittedCalls < 1 || minimumNumberOfCalls < halfOpenPermittedCalls) {
            throw new IllegalArgumentException("弹幕熔断时间窗口与样本数无效");
        }
        validatePercentage(failureRateThreshold);
        validatePercentage(slowCallRateThreshold);
        if (slowCallDurationThreshold.toNanos() < 1 || openInitialWait.toMillis() < 1
                || openMaxWait.compareTo(openInitialWait) < 0 || halfOpenMaxWait.toMillis() < 1) {
            throw new IllegalArgumentException("弹幕熔断耗时阈值或等待时间无效");
        }
    }

    private static void validatePercentage(float value) {
        if (!Float.isFinite(value) || value <= 0 || value > 100) {
            throw new IllegalArgumentException("弹幕熔断百分比必须大于 0 且不超过 100");
        }
    }
}
