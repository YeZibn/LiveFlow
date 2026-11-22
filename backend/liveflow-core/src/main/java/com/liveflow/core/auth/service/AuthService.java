package com.liveflow.core.auth.service;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.mapper.UserMapper;
import com.liveflow.core.auth.session.AuthSessionStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 统一编排密码认证、凭证签发及登录会话变更。 */
@Service
public class AuthService {

    private final UserMapper users;
    private final AuthSessionStore sessions;
    private final AccessTokenService tokens;
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    // 缺少账号时也执行同样的密码哈希工作，保持凭证错误路径一致。
    private final String absentAccountHash = passwords.encode(UUID.randomUUID().toString());

    /**
     * @param users 用于读取账号及密码哈希的查询入口
     * @param sessions 当前登录会话的权威仓储
     * @param tokens 用于签发短期访问凭证的组件
     */
    public AuthService(UserMapper users, AuthSessionStore sessions, AccessTokenService tokens) {
        this.users = users;
        this.sessions = sessions;
        this.tokens = tokens;
    }

    /**
     * 校验密码并将新会话登记为账号唯一的当前会话。
     * @param username 用户提交的 ASCII 用户名
     * @param password 用户提交的原始密码
     * @param source 可信连接的来源地址
     * @return 已提交会话的客户端凭证和有效期
     * @throws AuthFailure 输入无效、密码错误、限流或无法确认会话
     */
    public Credentials login(String username, String password, String source) {
        String canonical = username == null ? "" : username.toLowerCase(Locale.ROOT);
        if (username == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(username)
                || !canonical.matches("[a-z0-9][a-z0-9_]{2,31}") || password == null || password.isEmpty()
                || password.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new AuthFailure(INVALID_REQUEST);
        }
        // 先按规范化账号和连接来源限流，超额请求在查库及 Argon2 校验前返回。
        sessions.checkLoginLimit(canonical, source);
        var account = users.findForLogin(canonical);
        boolean matches = passwords.matches(password, account == null ? absentAccountHash : account.passwordHash());
        if (account == null || !matches) {
            throw new AuthFailure(AUTH_INVALID_CREDENTIALS);
        }

        // Redis 以毫秒保存截止时间，首发和刷新返回同一精度。
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = now.plus(Duration.ofDays(7));
        var refresh = RefreshToken.issue(account.id(), UUID.randomUUID());
        // 签发失败时保留旧会话，Redis 确认替换后才向客户端交付新凭证。
        Jwt jwt = tokens.issue(refresh.userId(), refresh.sessionId(), expiresAt);
        sessions.replace(refresh.userId(), refresh.sessionId(), refresh.digest(), now, expiresAt);
        return new Credentials(jwt, refresh.sessionId(), expiresAt, refresh.value());
    }

    /**
     * 保持原会话截止时间，轮换 Refresh Token 后签发新的 Access Token。
     * @param cookie 浏览器现有 Refresh Cookie 值
     * @return 新凭证及原有会话信息
     * @throws AuthFailure 凭证不可用、已重放或依赖不可用
     */
    public Credentials refresh(String cookie) {
        var current = RefreshToken.parse(cookie);
        var next = RefreshToken.issue(current.userId(), current.sessionId());
        // 在 Redis 一次性消费旧摘要，并取回原会话的截止时间。
        Instant expiresAt = sessions.rotate(current.userId(), current.sessionId(), current.digest(), next.digest());
        Jwt jwt = tokens.issue(next.userId(), next.sessionId(), expiresAt);
        return new Credentials(jwt, next.sessionId(), expiresAt, next.value());
    }

    /**
     * 撤销页面明确指向的登录会话。
     * @param target 页面正在操作的会话 ID
     * @param cookie 浏览器当前 Refresh Cookie 值
     * @throws AuthFailure 目标错误、已失效或撤销无法确认
     */
    public void logout(UUID target, String cookie) {
        if (target == null) {
            throw new AuthFailure(INVALID_REQUEST);
        }
        var refresh = RefreshToken.parse(cookie);
        // Cookie 可能被另一标签页更新，退出仍以页面提交的会话为目标。
        if (!target.equals(refresh.sessionId())) {
            throw new AuthFailure(AUTH_SESSION_CHANGED);
        }
        sessions.revoke(refresh.userId(), target, refresh.digest());
    }

    /**
     * 查询已认证用户的公开数据。
     * @param userId 从通过鉴权的 JWT 取得的用户 ID
     * @return 当前用户公开信息
     * @throws AuthFailure 账号已不存在
     */
    public UserMapper.Account currentUser(long userId) {
        var account = users.findById(userId);
        if (account == null) {
            throw new AuthFailure(AUTH_SESSION_INACTIVE);
        }
        return account;
    }

    /** 服务内部的凭证交付结果。Controller 负责将刷新凭证写入 HttpOnly Cookie。 */
    public record Credentials(Jwt access, UUID sessionId, Instant expiresAt, String refreshToken) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }
}
