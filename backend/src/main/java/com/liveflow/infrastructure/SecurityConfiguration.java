package com.liveflow.infrastructure;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 定义 LiveFlow 的 HTTP 请求授权规则。
 * WebSocket 协议升级前的 HTTP 握手也经过这条过滤链。
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /**
     * 构建并注册 Spring Security 过滤链。
     * 健康检查允许匿名访问，{@code local} profile 额外允许本地 WebSocket 测试端点的握手。
     * 其余请求由默认拒绝规则处理。
     *
     * @param http Spring Security 提供的 Web 安全构建器，用于声明授权规则
     * @param environment 当前 Spring 环境，用于判断 {@code local} profile 是否启用
     * @return 由 Spring Security 用于处理入站 HTTP 请求的过滤链
     * @throws Exception 配置授权规则或构建过滤链失败时抛出，应用无法完成启动
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        return http.authorizeHttpRequests(authorize -> {
            // 按 Actuator 端点 ID 匹配，管理端点的基础路径调整后仍能识别健康检查。
            authorize.requestMatchers(EndpointRequest.to("health")).permitAll();
            if (environment.matchesProfiles("local")) {
                authorize.requestMatchers("/ws/smoke").permitAll();
            }
            authorize.anyRequest().denyAll();
        }).build();
    }
}
