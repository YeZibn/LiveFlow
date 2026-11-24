package com.liveflow.infrastructure.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @param loginWindowSeconds 登录固定窗口秒数
 * @param loginAccountMax 登录账号额度
 * @param loginSourceMax 登录来源额度
 * @param registrationWindowSeconds 注册固定窗口秒数
 * @param registrationMax 注册来源额度
 * @param roomCooldownMillis 同账号两次新弹幕预约的最短毫秒间隔
 */
@Component
public record RateLimitSettings(
        @Value("${liveflow.resilience.rate-limit.login.window-seconds}") int loginWindowSeconds,
        @Value("${liveflow.resilience.rate-limit.login.account-max-attempts}") int loginAccountMax,
        @Value("${liveflow.resilience.rate-limit.login.source-max-attempts}") int loginSourceMax,
        @Value("${liveflow.resilience.rate-limit.registration.window-seconds}") int registrationWindowSeconds,
        @Value("${liveflow.resilience.rate-limit.registration.max-attempts}") int registrationMax,
        @Value("${liveflow.resilience.rate-limit.room-messages.cooldown-millis}") int roomCooldownMillis) {

    /** @throws IllegalArgumentException 任一额度或间隔小于 1 */
    public RateLimitSettings {
        if (loginWindowSeconds < 1 || loginAccountMax < 1 || loginSourceMax < 1
                || registrationWindowSeconds < 1 || registrationMax < 1 || roomCooldownMillis < 1) {
            throw new IllegalArgumentException("限流额度与时长必须为正数");
        }
    }
}
