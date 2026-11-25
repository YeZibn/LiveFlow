package com.liveflow.infrastructure.room;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.springframework.core.io.ClassPathResource;

/** Redis 命令和原子回填只通过独立的缓存连接执行。 */
final class RoomCacheRedis implements AutoCloseable {
    private final RoomCacheSettings settings;
    private final RedisURI uri;
    private final RedisClient client;
    private final String acquireScript = script("acquire-room-cache-fill.lua");
    private final String finishScript = script("finish-room-cache-fill.lua");
    private final String warmScript = script("warm-room-cache.lua");
    private volatile ConnectionFuture<StatefulRedisConnection<String, String>> connection;
    private long lastConnectStarted;

    /** @param settings 已启用的独立 Redis 和缓存生命周期配置 */
    RoomCacheRedis(RoomCacheSettings settings) {
        this.settings = settings;
        try {
            uri = RedisURI.create(settings.redis().uri());
            if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException failure) {
            // 连接地址可能包含凭证，配置异常只报告配置项。
            throw new IllegalArgumentException("liveflow.rooms.cache.redis.uri 必须为有效的独立 Redis 地址");
        }
        client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(256)
                .socketOptions(SocketOptions.builder().connectTimeout(settings.redis().connectTimeout()).build())
                .timeoutOptions(TimeoutOptions.enabled(settings.redis().commandTimeout()))
                .build());
        // 启动时先发起异步建连。连接失败不会阻塞就绪，也不会让首次业务请求独自建连。
        connection();
    }

    /** @param roomId 标准小写房间 UUID
     * @param budget 最长等待时间，也受单次命令上限约束
     * @return 已确认的存在性或未命中
     * @throws Failure 缓存读取失败或值无效
     */
    CachedResult read(String roomId, Duration budget) {
        return value(execute(budget, commands -> commands.get(keys(roomId)[0])));
    }

    /** @param roomId 标准小写房间 UUID
     * @return 复查结果、等待状态或本次回填的令牌和负缓存截止
     * @throws Failure 缓存读取或租约申请失败
     */
    Fill acquire(String roomId) {
        String token = UUID.randomUUID().toString();
        List<Object> response = execute(settings.redis().commandTimeout(), commands ->
                commands.eval(acquireScript, ScriptOutputType.MULTI, keys(roomId), token,
                        Long.toString(settings.fillLease().toMillis()),
                        Long.toString(settings.negativeTtl().sampleMillis())));
        return switch ((String) response.getFirst()) {
            case "value" -> new Fill(value((String) response.get(1)), null, 0);
            case "waiting" -> new Fill(CachedResult.MISS, null, 0);
            case "owner" -> new Fill(CachedResult.MISS, token, ((Number) response.get(1)).longValue());
            default -> throw new Failure();
        };
    }

    /** @param roomId 标准小写房间 UUID
     * @param fill 本次拥有的回填租约
     * @param exists 已确认的 MySQL 结果
     * @return 仍持有租约并完成本次收尾时为 true
     * @throws Failure 无法确认 Redis 执行结果
     */
    boolean finish(String roomId, Fill fill, boolean exists) {
        long expiration = exists ? settings.positiveTtl().sampleMillis() : fill.negativeDeadlineMs();
        return settle(roomId, fill, exists ? "1" : "0", expiration);
    }

    /** @param roomId 标准小写房间 UUID
     * @param fill 需要尝试释放的租约
     * @throws Failure 无法确认 Redis 执行结果，租约仍可自动到期
     */
    void release(String roomId, Fill fill) {
        settle(roomId, fill, "release", 0);
    }

    /** @param roomId 已确认提交的房间 UUID
     * @throws Failure 无法确认 Redis 执行结果
     */
    void warmCreated(String roomId) {
        execute(settings.redis().commandTimeout(), commands ->
                commands.eval(warmScript, ScriptOutputType.INTEGER, keys(roomId),
                        Long.toString(settings.positiveTtl().sampleMillis())));
    }

    private boolean settle(String roomId, Fill fill, String mode, long expiration) {
        Long result = execute(settings.redis().commandTimeout(), commands ->
                commands.eval(finishScript, ScriptOutputType.INTEGER, keys(roomId),
                        fill.token(), mode, Long.toString(expiration)));
        return result == 1;
    }

    private String[] keys(String roomId) {
        String prefix = settings.environment() + ":liveflow:room:exists:v1:{" + roomId + "}:";
        return new String[]{prefix + "value", prefix + "fill"};
    }

    private static CachedResult value(String content) {
        return switch (content) {
            case null -> CachedResult.MISS;
            case "1" -> CachedResult.PRESENT;
            case "0" -> CachedResult.ABSENT;
            default -> throw new Failure();
        };
    }

    private <T> T execute(Duration budget, Function<RedisAsyncCommands<String, String>, RedisFuture<T>> operation) {
        long deadline = System.nanoTime() + Math.min(budget.toNanos(), settings.redis().commandTimeout().toNanos());
        RedisFuture<T> command = null;
        try {
            // 获取共享连接和等待命令共用截止时间，不能在重连时重新计算一份预算。
            var connected = connection().get(remaining(deadline), TimeUnit.NANOSECONDS);
            remaining(deadline);
            command = operation.apply(connected.async());
            // 已完成的结果可以直接取出。线程恢复调度时无需再等待 Redis。
            return command.get(command.isDone() ? 0 : remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure(interrupted);
        } catch (TimeoutException | ExecutionException | RedisException | CancellationException failed) {
            throw new Failure(failed);
        } finally {
            // 超时不能确认命令未执行。Lua 用令牌保证迟到的写入仍安全。
            if (command != null && !command.isDone()) command.cancel(false);
        }
    }

    private ConnectionFuture<StatefulRedisConnection<String, String>> connection() {
        var current = connection;
        if (current != null && !current.toCompletableFuture().isCompletedExceptionally()) return current;
        synchronized (this) {
            current = connection;
            if (current != null && !current.toCompletableFuture().isCompletedExceptionally()) return current;
            long now = System.nanoTime();
            // 启动或建连失败时共享一次尝试，避免每个请求都创建连接。
            if (current == null || now - lastConnectStarted >= settings.redis().connectTimeout().toNanos()) {
                lastConnectStarted = now;
                connection = client.connectAsync(StringCodec.UTF8, uri);
            }
            return connection;
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }

    private static String script(String name) {
        try {
            return new ClassPathResource("room/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取房间缓存脚本 " + name, failure);
        }
    }

    /** 停止专用缓存连接，不影响业务 Redis 客户端。 */
    @Override
    public void close() {
        client.shutdown();
    }

    enum CachedResult { PRESENT, ABSENT, MISS }

    /** @param value 复查到的结果
     * @param token 本次取得的租约令牌，等待或已命中时为空
     * @param negativeDeadlineMs 用 Redis 时钟计算的负缓存绝对截止
     */
    record Fill(CachedResult value, String token, long negativeDeadlineMs) {}

    /** 缓存结果未知，交由调用方根据当前阶段决定是否回源。 */
    static final class Failure extends RuntimeException {
        private Failure() {}
        private Failure(Throwable cause) {
            super(cause);
        }
    }
}
