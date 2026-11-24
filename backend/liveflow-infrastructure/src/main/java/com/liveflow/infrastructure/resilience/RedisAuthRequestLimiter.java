package com.liveflow.infrastructure.resilience;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.resilience.AuthRequestLimiter;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import static com.liveflow.infrastructure.resilience.RateLimitMetrics.Outcome.*;
import static com.liveflow.infrastructure.resilience.RateLimitMetrics.Policy.*;

@Repository
public class RedisAuthRequestLimiter implements AuthRequestLimiter {

    private static final String LOGIN_PREFIX = "liveflow:auth:{login}:";
    private static final RedisScript<Long> FIXED_WINDOW =
            RedisScript.of(new ClassPathResource("resilience/fixed-window.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitSettings settings;
    private final RateLimitMetrics metrics;

    /**
     * @param redis 执行固定窗口脚本的连接
     * @param settings 认证及弹幕共享的限流配置
     * @param metrics 限流指标记录器
     */
    public RedisAuthRequestLimiter(StringRedisTemplate redis, RateLimitSettings settings, RateLimitMetrics metrics) {
        this.redis = redis;
        this.settings = settings;
        this.metrics = metrics;
    }

    @Override
    public void checkLoginLimit(String username, String source) {
        // 登录的两个键共用哈希标签，每次登录只执行一次 Lua。
        check(LOGIN, List.of(LOGIN_PREFIX + "account:" + username, LOGIN_PREFIX + "source:" + source),
                Integer.toString(settings.loginWindowSeconds()), Integer.toString(settings.loginAccountMax()),
                Integer.toString(settings.loginSourceMax()));
    }

    @Override
    public void checkRegistrationLimit(String source) {
        check(REGISTRATION, List.of("liveflow:auth:registration:source:" + source),
                Integer.toString(settings.registrationWindowSeconds()), Integer.toString(settings.registrationMax()));
    }

    /**
     * @param policy 要记录的认证策略
     * @param keys 一起裁决的计数键
     * @param arguments 窗口秒数及各键的额度上限
     * @throws AuthFailure 额度耗尽或 Redis 未返回有效结果
     */
    private void check(RateLimitMetrics.Policy policy, List<String> keys, String... arguments) {
        long started = System.nanoTime();
        Long waitMillis;
        try {
            waitMillis = redis.execute(FIXED_WINDOW, keys, (Object[]) arguments);
        } catch (DataAccessException failure) {
            metrics.record(policy, UNAVAILABLE, started);
            throw new AuthFailure(AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE);
        }
        if (waitMillis == null || waitMillis < 0) {
            metrics.record(policy, UNAVAILABLE, started);
            throw new AuthFailure(AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE);
        }
        if (waitMillis > 0) {
            metrics.record(policy, LIMITED, started);
            throw AuthFailure.rateLimited(Math.ceilDiv(waitMillis, 1000L));
        }
        metrics.record(policy, ALLOWED, started);
    }
}
