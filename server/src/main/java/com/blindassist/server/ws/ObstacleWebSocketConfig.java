package com.blindassist.server.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 避障 WebSocket 配置：
 * - 客户端通过 /ws/obstacle 建立连接
 * - 持续推送图像帧（可自定义二进制协议）
 * - 服务端实时分析后，推送避障指令
 */
@Configuration
@EnableWebSocket
public class ObstacleWebSocketConfig implements WebSocketConfigurer {

    private final ObstacleWebSocketHandler handler;

    public ObstacleWebSocketConfig(ObstacleWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/obstacle").setAllowedOrigins("*");
    }
}


