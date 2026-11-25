package com.liveflow.infrastructure.room;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 房间存在性缓存与回源的实例配置。 */
@ConfigurationProperties("liveflow.rooms.cache")
record RoomCacheSettings(boolean enabled, String environment, Redis redis, Ttl positiveTtl, Ttl negativeTtl,
        Duration fillLease, Duration waitTimeout, int sourceRate, int sourceBurst, int sourceConcurrency) {
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    RoomCacheSettings {
        if (environment == null || !ENVIRONMENT.matcher(environment).matches()) {
            throw new IllegalArgumentException("房间缓存环境名必须使用 1–64 个 ASCII 字母、数字、下划线、点或连字符");
        }
        validateDuration(fillLease, "fill-lease");
        validateDuration(waitTimeout, "wait-timeout");
        if (positiveTtl == null || negativeTtl == null || redis == null) {
            throw new IllegalArgumentException("房间缓存需要正负 TTL 及 Redis 配置");
        }
        if (redis.commandTimeout().compareTo(waitTimeout) >= 0 || waitTimeout.compareTo(fillLease) >= 0) {
            throw new IllegalArgumentException("缓存命令超时必须小于等待上限，等待上限必须小于回填租约时长");
        }
        if (sourceRate < 1 || sourceBurst < 1 || sourceConcurrency < 1) {
            throw new IllegalArgumentException("房间存在性回源的速率、突发和并发额度必须为正数");
        }
    }

    /** @param uri 独立缓存 Redis 连接地址，凭证由部署注入
     * @param connectTimeout 底层连接超时
     * @param commandTimeout 一次缓存操作的等待上限
     */
    record Redis(String uri, Duration connectTimeout, Duration commandTimeout) {
        Redis {
            validateDuration(connectTimeout, "redis.connect-timeout");
            validateDuration(commandTimeout, "redis.command-timeout");
        }
    }

    /** @param minimum TTL 最小值
     * @param maximum TTL 最大值，允许等于最小值
     */
    record Ttl(Duration minimum, Duration maximum) {
        Ttl {
            validateDuration(minimum, "TTL.minimum");
            validateDuration(maximum, "TTL.maximum");
            if (minimum.compareTo(maximum) > 0) throw new IllegalArgumentException("房间缓存 TTL 区间逆序");
        }

        /** @return 本次写入的随机 TTL，单位为毫秒 */
        long sampleMillis() {
            return ThreadLocalRandom.current().nextLong(minimum.toMillis(), maximum.toMillis() + 1);
        }
    }

    private static void validateDuration(Duration duration, String key) {
        // 本地等待使用纳秒计时，Redis 的 TTL 精度为毫秒。拒绝换算时会溢出或取整为零的值。
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0
                || duration.compareTo(Duration.ofNanos(Long.MAX_VALUE / 2)) > 0) {
            throw new IllegalArgumentException("房间缓存时间配置无效: " + key);
        }
    }
}
