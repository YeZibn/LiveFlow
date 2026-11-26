package com.liveflow.api.auth.controller;

import com.liveflow.api.auth.security.CsrfProtection;
import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.service.AuthService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 认证 HTTP 入口。JSON 只交付短期访问凭证，刷新凭证通过 Cookie 交付。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final CsrfProtection csrf;
    private final Counter csrfIssuanceFailed;

    /**
     * @param auth 认证业务入口
     * @param csrf 签发各用途的 CSRF Cookie
     * @param meters 应用指标注册表
     */
    public AuthController(AuthService auth, CsrfProtection csrf, MeterRegistry meters) {
        this.auth = auth;
        this.csrf = csrf;
        csrfIssuanceFailed = meters.counter("liveflow.auth.csrf.issuance.failed");
    }

    /**
     * 按请求用途初始化匿名凭证，或补领已确认会话的 CSRF。
     * @param query 只允许出现一次的 scope 枚举
     * @param request 包含对应的预会话或 Refresh Cookie
     * @param jwt 由安全链校验的可选 Bearer
     * @return 不缓存的 204 响应及适用的 Set-Cookie
     * @throws AuthFailure 参数无效或无法确认补领资格
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(@RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request, @AuthenticationPrincipal Jwt jwt) {
        try {
            return issueCsrf(query, request, jwt);
        } catch (AuthFailure failure) {
            csrfIssuanceFailed.increment();
            throw failure;
        }
    }

    private ResponseEntity<Void> issueCsrf(MultiValueMap<String, String> query, HttpServletRequest request, Jwt jwt) {
        var scope = query.get("scope");
        if (query.size() != 1 || scope == null || scope.size() != 1) {
            throw new AuthFailure(AuthFailure.Code.INVALID_REQUEST);
        }
        var response = ResponseEntity.noContent().cacheControl(CacheControl.noStore());
        switch (scope.getFirst()) {
            case "pre-auth" -> csrf.anonymousCookies(request).forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
            case "session" -> {
                // Bearer 已经由安全链验证。无 Bearer 时只读核对本次刷新会用的当前摘要。
                var session = jwt == null ? auth.currentRefreshSession(CsrfProtection.refreshCookie(request))
                        : auth.currentAccessSession(Long.parseLong(jwt.getSubject()),
                                UUID.fromString(jwt.getClaimAsString("sid")));
                response.header(HttpHeaders.SET_COOKIE, csrf.sessionCookie(session.sessionId(), session.expiresAt()));
            }
            default -> throw new AuthFailure(AuthFailure.Code.INVALID_REQUEST);
        }
        return response.build();
    }

    /**
     * 创建账号并返回公开摘要。
     * @param body 含账号名和一份密码的 JSON 请求体
     * @param request 用于读取可信来源地址
     * @return 账号提交成功后的 201 响应
     * @throws AuthFailure 请求非法、账号重复或注册限流
     * @throws DataAccessException 账号存储不可用
     */
    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CurrentUserResponse> register(@RequestBody JsonNode body, HttpServletRequest request) {
        // 只接受两个明确的字符串字段，避免隐式类型转换或接收确认密码。
        if (!body.isObject() || body.size() != 2 || !body.path("username").isString()
                || !body.path("password").isString()) {
            throw new AuthFailure(AuthFailure.Code.INVALID_REQUEST);
        }
        var account = auth.register(body.get("username").asString(), body.get("password").asString(), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new CurrentUserResponse(Long.toString(account.id()), account.username()));
    }

    /**
     * 登录并在提交成功后交付新凭证。
     * @param input 用户名与原始密码
     * @param request 用于读取可信来源地址
     * @return Access Token 正文及 Refresh Cookie
     * @throws AuthFailure 输入无效、凭证错误、登录限流或会话无法确认
     * @throws DataAccessException 账号存储不可用
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest input,
            HttpServletRequest request) {
        var credentials = auth.login(input.username(), input.password(), request.getRemoteAddr());
        var response = tokenResponse(credentials);
        response.header(HttpHeaders.SET_COOKIE, csrf.sessionCookie(credentials.sessionId(), credentials.expiresAt()));
        csrf.clearAnonymous().forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));
        return response.body(tokenBody(credentials));
    }

    /**
     * 轮换刷新凭证，保留原登录会话。
     * @param request 包含已通过 CSRF 绑定校验的 Refresh Cookie
     * @return Access Token 正文及新的 Refresh Cookie
     * @throws AuthFailure Cookie 缺失、无效、被重放或会话存储不可用
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        var credentials = auth.refresh(CsrfProtection.refreshCookie(request));
        return tokenResponse(credentials).body(tokenBody(credentials));
    }

    /**
     * 只撤销指定会话。清除 Cookie 之前必须确认服务端已经撤销。
     * @param input 页面所见的会话 ID
     * @param request 包含已通过 CSRF 绑定校验的 Refresh Cookie
     * @return 清除 Refresh 与认证态 CSRF Cookie 的 204 响应
     * @throws AuthFailure 目标不匹配、Cookie 无效或撤销无法确认
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody LogoutRequest input, HttpServletRequest request) {
        // 撤销失败时保留 Cookie，迟到的旧页退出不能清除新登录。
        auth.logout(input.sessionId(), CsrfProtection.refreshCookie(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0), csrf.clearSession()).build();
    }

    /**
     * 从安全上下文确定调用者并读取账号摘要。
     * @param jwt 签名、时效和 Redis 会话均验证通过的 JWT
     * @return 用户 ID 的字符串表示和用户名
     * @throws AuthFailure 认证身份对应的账号已不存在
     * @throws DataAccessException 账号存储不可用
     */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        var account = auth.currentUser(Long.parseLong(jwt.getSubject()));
        return new CurrentUserResponse(Long.toString(account.id()), account.username());
    }

    /** @param credentials 已提交的会话凭证
     *  @return 附 Refresh Cookie 和缓存控制的响应构建器 */
    private ResponseEntity.BodyBuilder tokenResponse(AuthService.Credentials credentials) {
        // 刷新 Cookie 只使用会话的剩余秒数，轮换不延长登录。
        long remaining = Math.max(0, Duration.between(Instant.now(), credentials.expiresAt()).toSeconds());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(credentials.refreshToken(), remaining));
    }

    /** @param credentials 已提交的会话凭证
     *  @return 只包含可由页面读取的短期凭证和会话摘要 */
    private TokenResponse tokenBody(AuthService.Credentials credentials) {
        Jwt jwt = credentials.access();
        long expiresIn = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds();
        return new TokenResponse(jwt.getTokenValue(), "Bearer", expiresIn,
                credentials.sessionId(), credentials.expiresAt());
    }

    /**
     * 序列化认证接口使用的刷新 Cookie。
     * @param value 原始刷新凭证，清除 Cookie 时传入空串
     * @param maxAgeSeconds Cookie 剩余秒数，传入 0 时清除
     * @return 可直接写入 Set-Cookie 的值
     */
    private String refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(CsrfProtection.REFRESH_COOKIE, value).httpOnly(true).secure(csrf.secureCookies())
                .sameSite("Lax").path("/api/v1/auth").maxAge(maxAgeSeconds).build().toString();
    }

    /**
     * 密码登录请求。
     * @param username 保留大小写的原始账号名
     * @param password 用户提交的密码，不显示在请求对象的字符串表示中
     */
    public record LoginRequest(String username, String password) {
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    /**
     * 定向退出请求。
     * @param sessionId 页面点击退出时持有的会话 ID
     */
    public record LogoutRequest(UUID sessionId) {}

    /**
     * 登录和刷新共用的 Access Token 响应。
     * @param accessToken 短期 JWT，由客户端保留在内存
     * @param tokenType Authorization 请求头使用的 Bearer 类型
     * @param expiresIn JWT 从签发到到期的有效秒数
     * @param sessionId 浏览器可用来识别会话变更的非秘密标识
     * @param sessionExpiresAt 整次登录的绝对截止时间，刷新后保持原值
     */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
            UUID sessionId, Instant sessionExpiresAt) {
        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }

    /**
     * 注册成功或当前账号查询返回的账号摘要。
     * @param userId 十进制字符串形式的账号 ID
     * @param username 保留原拼写的账号名
     */
    public record CurrentUserResponse(String userId, String username) {}
}
