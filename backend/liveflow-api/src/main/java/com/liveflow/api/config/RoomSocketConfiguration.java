package com.liveflow.api.config;

import com.liveflow.api.room.message.RoomSocketHandler;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** 注册房间接收端点及其资源维护任务。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableScheduling
class RoomSocketConfiguration implements WebSocketConfigurer {

    private final RoomSocketHandler handler;
    private final List<String> allowedOrigins;

    /**
     * @param handler 房间实时连接与消息分发处理器
     * @param origins 用逗号分隔的精确来源，为空时使用 Spring 同源校验
     */
    RoomSocketConfiguration(RoomSocketHandler handler,
            @Value("${liveflow.room-messages.allowed-origin:}") String origins) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 注册房间接收路径，握手时检查浏览器来源，登录会话由升级后的首帧票据确认。
     * @param registry Spring WebSocket 路由和来源校验注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var route = registry.addHandler(handler, "/ws/rooms").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
                String origin = request.getHeaders().getOrigin();
                if (origin == null || origin.isBlank() || "null".equals(origin)
                        || (!allowedOrigins.isEmpty() && !allowedOrigins.contains(origin))) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return false;
                }
                // 浏览器必须提供来源。其余校验沿用 Spring 的同源或显式来源规则。
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler wsHandler, Exception failure) {
                // 来源校验无需保留握手后的状态。
            }
        });
        // 显式配置时同时交给 Spring 校验。空列表保留框架默认的同源约束。
        if (!allowedOrigins.isEmpty()) {
            route.setAllowedOrigins(allowedOrigins.toArray(String[]::new));
        }
    }
}
