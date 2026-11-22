package com.liveflow.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 注册仅在 {@code local} profile 下启用的 WebSocket 测试端点。
 * 开发检查页借此验证浏览器经 Vite 代理到 Spring WebSocket 的握手和双向文本收发。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@Profile("local")
class LocalWebSocketConfiguration implements WebSocketConfigurer {

    /** 允许的前端页面来源，由协议、主机和端口组成，用于校验握手请求的 Origin。 */
    private final String frontendOrigin;

    /**
     * 保存本地 WebSocket 握手允许的前端来源。
     *
     * @param frontendOrigin 从 {@code liveflow.local.frontend-origin} 读取的来源，例如 {@code http://127.0.0.1:5173}
     */
    LocalWebSocketConfiguration(@Value("${liveflow.local.frontend-origin}") String frontendOrigin) {
        this.frontendOrigin = frontendOrigin;
    }

    /**
     * 在 Spring 初始化 WebSocket 映射时，将 {@code /ws/smoke} 注册为文本消息端点。
     * 注册时为浏览器握手配置允许的前端来源。
     *
     * @param registry Spring 提供的 WebSocket 处理器注册表，保存路径、处理器及来源限制
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 握手中的 Origin 来自浏览器打开的前端页面，因此这里配置 Vite 的地址。
        registry.addHandler(new SmokeHandler(), "/ws/smoke").setAllowedOrigins(frontendOrigin);
    }

    /** 处理测试端点的应用层文本探测消息，响应写回发起探测的连接。 */
    private static final class SmokeHandler extends TextWebSocketHandler {

        /**
         * 收到文本 {@code ping} 时向同一连接回复 {@code pong} 并保持连接打开。
         * 收到其他文本时，以 {@link CloseStatus#BAD_DATA} 关闭连接。
         *
         * @param session 收到探测消息的 WebSocket 会话，用于发送回复或关闭连接
         * @param message 客户端发送的文本消息，探测命令取自消息载荷
         * @throws java.io.IOException 发送回复或关闭连接时发生 I/O 故障
         */
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            if ("ping".equals(message.getPayload())) {
                session.sendMessage(new TextMessage("pong"));
                return;
            }
            session.close(CloseStatus.BAD_DATA);
        }
    }
}
