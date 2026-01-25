package com.blindassist.server.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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

    public UnifiedWebSocketConfig(AgentWebSocketHandler agentHandler,
                                  NavigationWebSocketHandler navigationHandler,
                                  ObstacleWebSocketHandler obstacleHandler) {
        this.agentHandler = agentHandler;
        this.navigationHandler = navigationHandler;
        this.obstacleHandler = obstacleHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 手机操控WebSocket (现有)
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
