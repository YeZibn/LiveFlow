package com.liveflow.api.auth.security;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在 Bearer 鉴权之后、MVC 处理之前按调用入口选择 CSRF 绑定依据。 */
public final class SessionCsrfFilter extends OncePerRequestFilter {
    private static final RequestMatcher ISSUANCE = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, "/api/v1/auth/csrf");
    private static final RequestMatcher ANONYMOUS = authWrites("register", "login");
    private static final RequestMatcher REFRESH_COOKIE = authWrites("refresh", "logout");

    private final CsrfProtection csrf;
    private final AuthService auth;
    private final AuthSecurityErrors errors;

    /**
     * @param csrf CSRF 凭证及来源校验
     * @param auth 用于解析 Refresh Cookie 所指的候选会话
     * @param errors 安全链的统一认证错误响应
     */
    public SessionCsrfFilter(CsrfProtection csrf, AuthService auth, AuthSecurityErrors errors) {
        this.csrf = csrf;
        this.auth = auth;
        this.errors = errors;
    }

    /** 内部异步重派发只传回既有结果。OncePerRequestFilter 默认跳过该派发。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (ISSUANCE.matches(request)) {
                csrf.requireOrigin(request);
            } else if (CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) {
                csrf.requireOrigin(request);
                verifyWrite(request);
            }
        } catch (AuthFailure failure) {
            errors.commence(request, response, failure);
            return;
        }
        chain.doFilter(request, response);
    }

    private void verifyWrite(HttpServletRequest request) {
        if (ANONYMOUS.matches(request)) {
            csrf.requireAnonymous(request);
        } else if (REFRESH_COOKIE.matches(request)) {
            // 此处只解析候选 sid，真正的摘要裁决在刷新或退出的原子操作中完成。
            csrf.requireSession(request, () -> auth.refreshSessionHint(CsrfProtection.refreshCookie(request)));
        } else if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            csrf.requireSession(request, () -> UUID.fromString(jwt.getToken().getClaimAsString("sid")));
        }
        // 未认证的业务写请求由后续授权规则拒绝。
    }

    private static RequestMatcher authWrites(String first, String second) {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(paths.matcher(HttpMethod.POST, "/api/v1/auth/" + first),
                paths.matcher(HttpMethod.POST, "/api/v1/auth/" + second));
    }
}
