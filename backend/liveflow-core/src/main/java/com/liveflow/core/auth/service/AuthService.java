package com.liveflow.core.auth.service;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.mapper.UserMapper;
import com.liveflow.core.auth.session.AuthSessionStore;
import com.liveflow.core.resilience.AuthRequestLimiter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 统一编排账号注册、密码认证、凭证签发及登录会话变更。 */
@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_]{2,31}");
    private static final Pattern REGISTRATION_PASSWORD =
            Pattern.compile("(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[!-~]{10,25}");

    private final UserMapper users;
    private final AuthSessionStore sessions;
    private final AuthRequestLimiter limits;
    private final AccessTokenService tokens;
    private final Argon2PasswordEncoder passwords = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    // 缺少账号时也执行同样的密码哈希工作，保持凭证错误路径一致。
    private final String absentAccountHash = passwords.encode(UUID.randomUUID().toString());

    /**
     * 创建认证编排服务并绑定账号存储、会话存储和 JWT 签发器。
     * @param users 用于创建账号及读取账号和密码哈希的入口
     * @param sessions 当前登录会话的权威仓储
     * @param limits 登录和注册额度裁决
     * @param tokens 用于签发短期访问凭证的组件
     */
    public AuthService(UserMapper users, AuthSessionStore sessions, AuthRequestLimiter limits, AccessTokenService tokens) {
        this.users = users;
        this.sessions = sessions;
        this.limits = limits;
        this.tokens = tokens;
    }

    /**
     * 创建账号。写入成功后返回账号摘要，登录入口随后建立会话。
     * @param username 用户提供的原样账号名
     * @param password 用户新设置的原始密码
     * @param source 可信连接的来源地址
     * @return 已写入的账号 ID 和原样账号名
     * @throws AuthFailure 输入无效、账号重复、限流或无法确认写入
     * @throws DataAccessException 账号存储不可用
     */
    public UserMapper.Account register(String username, String password, String source) {
        if (!validUsername(username) || password == null || password.length() < 10 || password.length() > 25
                || !REGISTRATION_PASSWORD.matcher(password).matches()) {
            throw new AuthFailure(INVALID_REQUEST);
        }
        // 先预留来源额度，超限请求无需计算 Argon2id。
        limits.checkRegistrationLimit(source);
        var account = new UserMapper.NewAccount(username, passwords.encode(password));
        try {
            // 直接插入以唯一索引裁决并发注册，避免先查后写产生竞态。
            if (users.insert(account) != 1 || account.id() == null) {
                throw new AuthFailure(AUTH_SERVICE_UNAVAILABLE);
            }
        } catch (DuplicateKeyException ex) {
            String reason = ex.getMostSpecificCause().getMessage();
            // 只有账号名唯一索引冲突等价于重名，其他重复键错误由通用异常映射处理。
            if (reason != null && (reason.endsWith(".uk_users_username'") || reason.endsWith("'uk_users_username'"))) {
                throw new AuthFailure(AUTH_USERNAME_ALREADY_EXISTS);
            }
            throw ex;
        }
        return new UserMapper.Account(account.id(), username);
    }

    /**
     * 校验密码并将新会话登记为账号唯一的当前会话。
     * @param username 用户提交的 ASCII 用户名
     * @param password 用户提交的原始密码
     * @param source 可信连接的来源地址
     * @return 已提交会话的客户端凭证和有效期
     * @throws AuthFailure 输入无效、密码错误、限流或无法确认会话
     * @throws DataAccessException 账号存储不可用
     * @throws org.springframework.security.oauth2.jwt.JwtEncodingException 无法签发 Access Token
     */
    public Credentials login(String username, String password, String source) {
        if (!validUsername(username) || password == null || password.isEmpty()
                || password.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new AuthFailure(INVALID_REQUEST);
        }
        // 限流键与数据库使用相同拼写，大小写不同的账号分别计数。
        limits.checkLoginLimit(username, source);
        var account = users.findForLogin(username);
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
     * @throws org.springframework.security.oauth2.jwt.JwtEncodingException 轮换后无法签发 Access Token
     */
    public Credentials refresh(String cookie) {
        var current = RefreshToken.parse(cookie);
        var next = RefreshToken.issue(current.userId(), current.sessionId());
        // 在 Redis 一次性消费旧摘要，并取回原会话的截止时间。
        Instant expiresAt = sessions.rotate(current.userId(), current.sessionId(), current.digest(), next.digest());
        // 到这里旧凭证已被消耗。若响应结果不明确，客户端应重新登录。
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
     * 查询当前调用者自己的账号摘要。
     * @param userId 从通过鉴权的 JWT 取得的用户 ID
     * @return 当前用户公开信息
     * @throws AuthFailure 账号已不存在
     * @throws DataAccessException 账号存储不可用
     */
    public UserMapper.Account currentUser(long userId) {
        var account = users.findById(userId);
        if (account == null) {
            throw new AuthFailure(AUTH_SESSION_INACTIVE);
        }
        return account;
    }

    /**
     * 校验账号名的字符集和长度，沿用提交时的大小写。
     * @param username 用户提交的原样账号名
     * @return 满足注册和登录共用的账号格式时为 true
     */
    private static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    /**
     * 服务内部的凭证交付结果。Controller 将刷新凭证写入 HttpOnly Cookie。
     * @param access 已签发的 Access Token
     * @param sessionId 服务端登记的登录会话 ID
     * @param expiresAt Redis 会话的绝对截止时间
     * @param refreshToken 待写入 Cookie 的原始刷新凭证
     */
    public record Credentials(Jwt access, UUID sessionId, Instant expiresAt, String refreshToken) {
        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }
}
