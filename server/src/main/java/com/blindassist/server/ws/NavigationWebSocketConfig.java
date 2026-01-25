package com.blindassist.server.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 导航WebSocket配置
 */
@Configuration
@EnableWebSocket
public class NavigationWebSocketConfig implements WebSocketConfigurer {

    private final NavigationWebSocketHandler navigationHandler;

    public NavigationWebSocketConfig(NavigationWebSocketHandler navigationHandler) {
        this.navigationHandler = navigationHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(navigationHandler, "/ws/navigation/{client_id}")
                .setAllowedOrigins("*");
    }
}
