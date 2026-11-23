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

    @Override
    public void replace(long userId, UUID sessionId, String digest, Instant createdAt, Instant expiresAt) {
        // 账号的当前会话和已用摘要由同一段 Lua 一次性替换。
        if (execute(REPLACE, List.of(key(userId)), sessionId.toString(), digest,
                Long.toString(createdAt.toEpochMilli()), Long.toString(expiresAt.toEpochMilli())) != 1) {
            throw new AuthFailure(AUTH_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Instant rotate(long userId, UUID sessionId, String digest, String nextDigest) {
        long result = execute(ROTATE, List.of(key(userId)), sessionId.toString(), digest, nextDigest);
        // Lua 用正值返回到期毫秒，0 表示凭证无效，-1 表示已识别重放并撤销会话。
        if (result <= 0) {
            throw new AuthFailure(result == -1 ? AUTH_REFRESH_REUSED : AUTH_REFRESH_INVALID);
        }
        return Instant.ofEpochMilli(result);
    }

    @Override
    public void revoke(long userId, UUID sessionId, String digest) {
        if (execute(REVOKE, List.of(key(userId)), sessionId.toString(), digest) != 1) {
            throw new AuthFailure(AUTH_REFRESH_INVALID);
        }
    }

    @Override
    public void checkLoginLimit(String username, String source) {
        String prefix = "liveflow:auth:{login}:";
        if (execute(LIMIT, List.of(prefix + "account:" + username, prefix + "source:" + source),
                "10", "60", "60") != 1) {
            throw new AuthFailure(AUTH_RATE_LIMITED);
        }
    }

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

    private static String key(long userId) {
        return "liveflow:auth:session:{" + userId + "}";
    }

    private static DefaultRedisScript<Long> script(String name) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("auth/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
