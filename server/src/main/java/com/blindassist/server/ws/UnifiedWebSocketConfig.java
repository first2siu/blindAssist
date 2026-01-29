package com.blindassist.server.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 统一WebSocket配置
 * 管理所有WebSocket端点
 */
@Configuration
@EnableWebSocket
public class UnifiedWebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentHandler;
    private final NavigationWebSocketHandler navigationHandler;
    private final ObstacleWebSocketHandler obstacleHandler;

    // WebSocket缓冲区大小配置（用于处理大型base64截图）
    // 默认8KB太小，需要增加到至少1MB
    private static final int MAX_TEXT_MESSAGE_BUFFER_SIZE = 1024 * 1024;  // 1MB
    private static final int MAX_BINARY_MESSAGE_BUFFER_SIZE = 1024 * 1024;  // 1MB

    public UnifiedWebSocketConfig(AgentWebSocketHandler agentHandler,
                                  NavigationWebSocketHandler navigationHandler,
                                  ObstacleWebSocketHandler obstacleHandler) {
        this.agentHandler = agentHandler;
        this.navigationHandler = navigationHandler;
        this.obstacleHandler = obstacleHandler;
    }

    /**
     * 配置WebSocket缓冲区大小
     * 默认缓冲区只有8KB，无法处理包含截图的大型消息
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_SIZE);
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_MESSAGE_BUFFER_SIZE);
        container.setMaxSessionIdleTimeout(300000L); // 5分钟超时
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 手机操控WebSocket (处理大型截图消息)
        registry.addHandler(agentHandler, "/ws/agent")
                .setAllowedOrigins("*");

        // 导航WebSocket (新增)
        registry.addHandler(navigationHandler, "/ws/navigation/{client_id}")
                .setAllowedOrigins("*");

        // 避障WebSocket (新增)
        registry.addHandler(obstacleHandler, "/ws/obstacle")
                .setAllowedOrigins("*");
    }
}
