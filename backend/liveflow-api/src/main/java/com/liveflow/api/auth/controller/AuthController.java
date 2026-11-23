package com.liveflow.api.auth.controller;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 认证 HTTP 入口。JSON 只交付短期访问凭证，刷新凭证通过 Cookie 交付。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String COOKIE = "lf_refresh";
    private final AuthService auth;
    private final CsrfTokenRepository csrfTokens;
    private final boolean secureCookie;

    /**
     * @param auth 认证业务入口
     * @param csrfTokens 与过滤链共享的 SPA CSRF 仓储
     * @param secureCookie 是否限制刷新 Cookie 只在安全传输中发送
     */
    public AuthController(AuthService auth, CsrfTokenRepository csrfTokens,
            @Value("${liveflow.auth.secure-cookie}") boolean secureCookie) {
        this.auth = auth;
        this.csrfTokens = csrfTokens;
        this.secureCookie = secureCookie;
    }

    /**
     * 解析延迟生成的 CSRF Token，确保响应携带可供 SPA 读取的 Cookie。
     * @param csrf 当前请求的延迟 CSRF Token
     * @return 无正文的 204 响应
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrf) {
        // 即使响应没有正文，也要取出延迟令牌，首次请求才会生成 CSRF Cookie。
        csrf.getToken();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
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
     * @param request 用于读取可信来源地址与清除旧 CSRF 状态
     * @param response 用于清除旧 CSRF Cookie
     * @return Access Token 正文及 Refresh Cookie
     * @throws AuthFailure 输入无效、凭证错误、登录限流或会话无法确认
     * @throws DataAccessException 账号存储不可用
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        var credentials = auth.login(input.username(), input.password(), request.getRemoteAddr());
        // 登录成功后清除旧 CSRF Cookie，客户端在下一次写请求前重新初始化。
        csrfTokens.saveToken(null, request, response);
        return tokenResponse(credentials);
    }

    /**
     * 轮换刷新凭证，保留原登录会话。
     * @param cookie 浏览器附带的当前刷新凭证
     * @return Access Token 正文及新的 Refresh Cookie
     * @throws AuthFailure Cookie 缺失、无效、被重放或会话存储不可用
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = COOKIE, required = false) String cookie) {
        return tokenResponse(auth.refresh(cookie));
    }

    /**
     * 只撤销指定会话。清除 Cookie 之前必须确认服务端已经撤销。
     * @param input 页面所见的会话 ID
     * @param cookie 浏览器附带的刷新凭证
     * @param request 当前请求
     * @param response 用于清除旧 CSRF Cookie
     * @return 写入过期 Refresh Cookie 的 204 响应
     * @throws AuthFailure 目标不匹配、Cookie 无效或撤销无法确认
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody LogoutRequest input,
            @CookieValue(name = COOKIE, required = false) String cookie,
            HttpServletRequest request, HttpServletResponse response) {
        // 撤销失败会在这里抛出，保留浏览器当前的 Cookie。
        auth.logout(input.sessionId(), cookie);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", 0)).build();
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

    /**
     * 为登录和刷新构造统一响应，并将两种凭证写入各自的交付渠道。
     * @param credentials 业务层已确认的凭证和会话期限
     * @return 带 no-store、Access Token 正文和刷新 Cookie 的响应
     */
    private ResponseEntity<TokenResponse> tokenResponse(AuthService.Credentials credentials) {
        Jwt jwt = credentials.access();
        long expiresIn = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds();
        // 刷新 Cookie 只使用会话的剩余时间，轮换不会延长这次登录。
        long remaining = Math.max(0, Duration.between(Instant.now(), credentials.expiresAt()).toSeconds());
        var body = new TokenResponse(jwt.getTokenValue(), "Bearer", expiresIn,
                credentials.sessionId(), credentials.expiresAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(credentials.refreshToken(), remaining)).body(body);
    }

    /**
     * 序列化认证接口使用的刷新 Cookie。
     * @param value 原始刷新凭证，清除 Cookie 时传入空串
     * @param maxAgeSeconds Cookie 剩余秒数，传入 0 时清除
     * @return 可直接写入 Set-Cookie 的值
     */
    private String refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secureCookie)
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
