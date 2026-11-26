package com.liveflow.api.auth.security;

import com.liveflow.core.auth.AuthFailure;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import static com.liveflow.core.auth.AuthFailure.Code.AUTH_CSRF_INVALID;

/** 将签名凭证映射到浏览器 Cookie、请求头和生产来源边界。 */
@Component
public final class CsrfProtection {
    public static final String REFRESH_COOKIE = "lf_refresh";
    private static final String HEADER = "X-XSRF-TOKEN";
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private final CsrfTokens tokens;
    private final boolean secure;
    private final Set<String> allowedOrigins;
    private final String preSessionCookie;
    private final String preCsrfCookie;
    private final String sessionCsrfCookie;
    private final Counter rejected;

    /**
     * @param environment 仅 local 环境可显式选择回环 HTTP Cookie
     * @param secureCookie 是否启用安全 Cookie
     * @param origins 逗号分隔的完整前端 Origin
     * @param keyId 当前签发密钥标识
     * @param key 当前签发密钥的 Base64 值
     * @param previous 其他可用验签密钥的编码
     * @param meters 应用指标注册表
     */
    public CsrfProtection(Environment environment, @Value("${liveflow.auth.secure-cookie}") boolean secureCookie,
            @Value("${liveflow.auth.csrf.allowed-origins}") String origins,
            @Value("${liveflow.auth.csrf.key-id}") String keyId,
            @Value("${liveflow.auth.csrf.signing-key}") String key,
            @Value("${liveflow.auth.csrf.verification-keys}") String previous, MeterRegistry meters) {
        boolean local = environment.matchesProfiles("local");
        secure = !local || secureCookie;
        String prefix = secure ? "__Host-" : "";
        preSessionCookie = prefix + "lf_pre";
        preCsrfCookie = prefix + "lf_csrf_pre";
        sessionCsrfCookie = prefix + "lf_csrf";
        allowedOrigins = Set.copyOf(Arrays.stream(origins.split(",", -1)).map(String::trim).toList());
        for (String value : allowedOrigins) {
            String normalized = originOf(value, local);
            if (!value.equals(normalized)) throw new IllegalArgumentException("CSRF Origin 配置必须是完整的受支持来源");
        }
        tokens = new CsrfTokens(keyId, key, previous, Clock.systemUTC());
        rejected = meters.counter("liveflow.auth.csrf.rejected");
    }

    /**
     * @param request 浏览器写请求或签发请求
     * @throws AuthFailure 来源头不可信或出现重复
     */
    public void requireOrigin(HttpServletRequest request) {
        String origin = singleHeader(request, HttpHeaders.ORIGIN);
        if (origin != null) {
            if (!allowedOrigins.contains(origin)) throw invalid();
            return;
        }
        String referer = singleHeader(request, HttpHeaders.REFERER);
        if (referer != null) {
            String source = originOf(referer, true);
            if (source == null || !allowedOrigins.contains(source)) throw invalid();
        }
    }

    /**
     * @param request 匿名初始化请求
     * @return 需要写入的 Cookie，凭证对仍有效时为空
     */
    public List<String> anonymousCookies(HttpServletRequest request) {
        if (tokens.validAnonymous(cookie(request, preSessionCookie), cookie(request, preCsrfCookie))) return List.of();
        var issued = tokens.issueAnonymous();
        long remaining = remaining(issued.expiresAt());
        return List.of(setCookie(preSessionCookie, issued.preSession(), remaining, true),
                setCookie(preCsrfCookie, issued.token(), remaining, false));
    }

    /** @param request 注册或登录请求
     *  @throws AuthFailure 匿名预会话或 CSRF 无效 */
    public void requireAnonymous(HttpServletRequest request) {
        String submitted = submitted(request, preCsrfCookie);
        if (!tokens.validAnonymous(cookie(request, preSessionCookie), submitted)) throw invalid();
    }

    /**
     * @param request 登录后业务写请求、刷新或退出
     * @param sessionId 会话绑定的来源，可能抛出认证错误
     * @throws AuthFailure 缺少有效认证态 CSRF 或无法确定凭证所指会话
     */
    public void requireSession(HttpServletRequest request, Supplier<UUID> sessionId) {
        String submitted = submitted(request, sessionCsrfCookie);
        if (!tokens.validSession(submitted, sessionId.get())) throw invalid();
    }

    /** @param sessionId 已核对的当前会话
     *  @param expiresAt 原登录会话的绝对截止
     *  @return 带会话剩余时长的认证态 Set-Cookie 值 */
    public String sessionCookie(UUID sessionId, Instant expiresAt) {
        return setCookie(sessionCsrfCookie, tokens.issueSession(sessionId), remaining(expiresAt), false);
    }

    /** @return 登录确认后用于清除匿名凭证的两个 Set-Cookie 值 */
    public List<String> clearAnonymous() {
        return List.of(setCookie(preSessionCookie, "", 0, true), setCookie(preCsrfCookie, "", 0, false));
    }

    /** @return 退出确认后用于清除认证态 CSRF 的 Set-Cookie 值 */
    public String clearSession() {
        return setCookie(sessionCsrfCookie, "", 0, false);
    }

    /** @return 仅显式配置的 local 回环 HTTP 可以关闭 Secure */
    public boolean secureCookies() {
        return secure;
    }

    /** @param request 认证入口请求
     *  @return 唯一的 Refresh Cookie 原值，缺失或重复时为空 */
    public static String refreshCookie(HttpServletRequest request) {
        return cookie(request, REFRESH_COOKIE);
    }

    private String submitted(HttpServletRequest request, String name) {
        String token = singleHeader(request, HEADER);
        String cookie = cookie(request, name);
        // 长度限制同时约束规范检查与常量时间比较前的内存分配。
        if (token == null || cookie == null || token.length() > CsrfTokens.MAX_TOKEN_LENGTH
                || cookie.length() > CsrfTokens.MAX_TOKEN_LENGTH
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        cookie.getBytes(StandardCharsets.UTF_8))) throw invalid();
        return token;
    }

    private String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements()) throw invalid();
        return value;
    }

    private AuthFailure invalid() {
        rejected.increment();
        return new AuthFailure(AUTH_CSRF_INVALID);
    }

    private String setCookie(String name, String value, long maxAge, boolean httpOnly) {
        return ResponseCookie.from(name, value).httpOnly(httpOnly).secure(secure)
                .sameSite("Lax").path("/").maxAge(maxAge).build().toString();
    }

    private static long remaining(Instant expiresAt) {
        return Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds());
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String value = null;
        for (Cookie entry : cookies) {
            if (!name.equals(entry.getName())) continue;
            if (value != null) return null;
            value = entry.getValue();
        }
        return value;
    }

    private static String originOf(String value, boolean allowLoopbackHttp) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getRawUserInfo() != null) return null;
            if (!"https".equals(uri.getScheme()) && !(allowLoopbackHttp && "http".equals(uri.getScheme())
                    && LOOPBACK.contains(uri.getHost()))) return null;
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
