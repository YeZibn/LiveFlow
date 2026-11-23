package com.liveflow.infrastructure.auth;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.session.AuthSessionStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 在 Redis 中读写每个账号唯一的当前会话。 */
@Repository
public class AuthSessionRepository implements AuthSessionStore {

    private static final DefaultRedisScript<Long> REPLACE = script("replace-session");
    private static final DefaultRedisScript<Long> ROTATE = script("rotate-refresh");
    private static final DefaultRedisScript<Long> REVOKE = script("revoke-session");
    private static final DefaultRedisScript<Long> LIMIT = script("limit-login");
    private static final DefaultRedisScript<Long> REGISTRATION_LIMIT = script("limit-registration");
    private final StringRedisTemplate redis;
    private final String registrationMaxAttempts;
    private final String registrationWindowSeconds;

    /**
     * @param redis 认证状态的 Redis 客户端
     * @param registrationMaxAttempts 同一来源在注册窗口内允许的提交次数
     * @param registrationWindowSeconds 固定窗口时长，单位为秒
     * @throws IllegalArgumentException 限流次数或时长不是正数
     */
    public AuthSessionRepository(StringRedisTemplate redis,
            @Value("${liveflow.auth.registration-limit.max-attempts:10}") int registrationMaxAttempts,
            @Value("${liveflow.auth.registration-limit.window-seconds:600}") int registrationWindowSeconds) {
        if (registrationMaxAttempts <= 0 || registrationWindowSeconds <= 0) {
            throw new IllegalArgumentException("注册限流次数和窗口秒数必须为正数");
        }
        this.redis = redis;
        this.registrationMaxAttempts = Integer.toString(registrationMaxAttempts);
        this.registrationWindowSeconds = Integer.toString(registrationWindowSeconds);
    }

    /**
     * {@inheritDoc}
     * 只读取 sessionId 字段。键缺失表示会话过期或已撤销，ID 不同表示已被替换。
     */
    @Override
    public void requireCurrent(long userId, UUID sessionId) {
        Object current;
        try {
            current = redis.opsForHash().get(key(userId), "sessionId");
        } catch (DataAccessException ex) {
            throw new AuthFailure(AUTH_SERVICE_UNAVAILABLE);
        }
        if (current == null) {
            throw new AuthFailure(AUTH_SESSION_INACTIVE);
        }
        if (!sessionId.toString().equals(current)) {
            throw new AuthFailure(AUTH_SESSION_REPLACED);
        }
    }

    /** {@inheritDoc} Lua 在一次执行中写入新会话和绝对到期时间。 */
    @Override
    public void replace(long userId, UUID sessionId, String digest, Instant createdAt, Instant expiresAt) {
        // 账号的当前会话和已用摘要由同一段 Lua 一次性替换。
        if (execute(REPLACE, List.of(key(userId)), sessionId.toString(), digest,
                Long.toString(createdAt.toEpochMilli()), Long.toString(expiresAt.toEpochMilli())) != 1) {
            throw new AuthFailure(AUTH_SERVICE_UNAVAILABLE);
        }
    }

    /** {@inheritDoc} 先匹配会话 ID，再处理摘要，旧会话的凭证无法撤销接任会话。 */
    @Override
    public Instant rotate(long userId, UUID sessionId, String digest, String nextDigest) {
        long result = execute(ROTATE, List.of(key(userId)), sessionId.toString(), digest, nextDigest);
        // Lua 用正值返回到期毫秒，0 表示凭证无效，-1 表示已识别重放并撤销会话。
        if (result <= 0) {
            throw new AuthFailure(result == -1 ? AUTH_REFRESH_REUSED : AUTH_REFRESH_INVALID);
        }
        return Instant.ofEpochMilli(result);
    }

    /** {@inheritDoc} 当前摘要和此会话已消费的摘要都可确认退出请求的归属。 */
    @Override
    public void revoke(long userId, UUID sessionId, String digest) {
        if (execute(REVOKE, List.of(key(userId)), sessionId.toString(), digest) != 1) {
            throw new AuthFailure(AUTH_REFRESH_INVALID);
        }
    }

    /** {@inheritDoc} 同一脚本原子检查并预留账号和来源两个固定窗口的额度。 */
    @Override
    public void checkLoginLimit(String username, String source) {
        // 两个键共享 Redis Cluster 的哈希标签，保证可在同一个 Lua 脚本中操作。
        String prefix = "liveflow:auth:{login}:";
        if (execute(LIMIT, List.of(prefix + "account:" + username, prefix + "source:" + source),
                "10", "60", "60") != 1) {
            throw new AuthFailure(AUTH_RATE_LIMITED);
        }
    }

    /** {@inheritDoc} 将脚本返回的毫秒等待时间向上取整，供 Retry-After 使用。 */
    @Override
    public void checkRegistrationLimit(String source) {
        // 注册只按来源计数，登录仍使用自身的来源和账号窗口。
        long waitMs = execute(REGISTRATION_LIMIT, List.of("liveflow:auth:registration:source:" + source),
                registrationMaxAttempts, registrationWindowSeconds);
        if (waitMs < 0) {
            throw new AuthFailure(AUTH_SERVICE_UNAVAILABLE);
        }
        if (waitMs > 0) {
            throw AuthFailure.registrationRateLimited(Math.ceilDiv(waitMs, 1000L));
        }
    }

    /**
     * 执行一次 Redis 脚本，并统一处理无法确认的执行结果。
     * @param script 对应会话变更或限流的 Lua 脚本
     * @param keys 脚本按顺序读取的 KEYS
     * @param args 脚本按顺序读取的 ARGV，时间单位由各脚本定义
     * @return 脚本返回的整数状态或时间值
     * @throws AuthFailure 存储不可用或脚本未返回可确认的结果
     */
    private long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        try {
            Long result = redis.execute(script, keys, (Object[]) args);
            if (result != null) {
                return result;
            }
        } catch (DataAccessException ex) {
            throw new AuthFailure(AUTH_SERVICE_UNAVAILABLE);
        }
        throw new AuthFailure(AUTH_SERVICE_UNAVAILABLE);
    }

    /**
     * 生成账号当前会话的 Redis 键。
     * @param userId 账号 ID，用作 Redis Cluster 哈希标签
     * @return 对应唯一会话记录的键
     */
    private static String key(long userId) {
        return "liveflow:auth:session:{" + userId + "}";
    }

    /**
     * 从类路径读取认证脚本并声明其整数返回类型。
     * @param name auth 目录中不带扩展名的 Lua 文件名
     * @return 可交给 StringRedisTemplate 执行的脚本
     */
    private static DefaultRedisScript<Long> script(String name) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("auth/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
