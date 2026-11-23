package com.liveflow.api.config;

import com.liveflow.core.auth.session.AuthSessionStore;
import com.liveflow.api.auth.security.AuthSecurityErrors;
import com.liveflow.core.auth.service.AccessTokenService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * 定义 LiveFlow 的 HTTP 请求授权规则。
 * WebSocket 协议升级前的 HTTP 握手也经过这条过滤链。
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /**
     * 为同源 SPA 提供可读取的 CSRF Cookie，以便写请求将值回传到请求头。
     * @return 与认证控制器和安全过滤链共用的 Cookie 仓储
     */
    @Bean
    CookieCsrfTokenRepository csrfTokens() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /**
     * 构建并注册 Spring Security 过滤链。
     * 健康检查允许匿名访问，{@code local} profile 额外允许本地 WebSocket 测试端点的握手。
     * 认证接口根据凭证用途分别校验 CSRF、JWT 与 Redis 当前会话，其余请求由默认拒绝规则处理。
     *
     * @param http Spring Security 提供的 Web 安全构建器，用于声明授权规则
     * @param environment 当前 Spring 环境，用于判断 {@code local} profile 是否启用
     * @param csrfTokens 同源 Vue 使用的 CSRF Cookie 仓储
     * @param decoder Access Token 的签名和声明校验器
     * @param sessions 认证会话的权威状态仓储
     * @param errors 过滤链共用的认证错误响应
     * @return 由 Spring Security 用于处理入站 HTTP 请求的过滤链
     * @throws Exception 配置授权规则或构建过滤链失败时抛出，应用无法完成启动
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment,
            CookieCsrfTokenRepository csrfTokens, AccessTokenService decoder,
            AuthSessionStore sessions, AuthSecurityErrors errors) throws Exception {
        // 当前登录由 JWT 与 Redis 会话决定，Servlet 会话无需保存认证状态或待恢复请求。
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens)
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public <F extends CsrfFilter> F postProcess(F filter) {
                                // Resource Server 默认豁免 Bearer 请求。本站的浏览器写接口仍需校验 CSRF。
                                filter.setRequireCsrfProtectionMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER);
                                return filter;
                            }
                        }))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(errors)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(token -> {
                            // 即使 JWT 尚未到期，账号的旧会话也必须被拒绝。
                            sessions.requireCurrent(Long.parseLong(token.getSubject()),
                                    UUID.fromString(token.getClaimAsString("sid")));
                            return new JwtAuthenticationToken(token, List.of());
                        })))
                .authorizeHttpRequests(authorize -> {
                    // 按 Actuator 端点 ID 匹配，管理端点的基础路径调整后仍能识别健康检查。
                    authorize.requestMatchers(EndpointRequest.to("health")).permitAll();
                    if (environment.matchesProfiles("local")) {
                        authorize.requestMatchers("/ws/smoke").permitAll();
                    }
                    authorize.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                    "/api/v1/auth/logout").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/users/me/profile").authenticated()
                            .requestMatchers(HttpMethod.PUT, "/api/v1/users/me/profile").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/users/me/room").authenticated()
                            .requestMatchers(HttpMethod.GET, "/api/v1/rooms", "/api/v1/rooms/{roomId}").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/v1/rooms", "/api/v1/rooms/{roomId}/ws-ticket",
                                    "/api/v1/rooms/{roomId}/messages").authenticated()
                            // HTTP 升级后先等待一次性票据，验票前连接不属于任何房间。
                            .requestMatchers(HttpMethod.GET, "/ws/rooms").permitAll()
                            .anyRequest().denyAll();
                }).build();
    }
}
