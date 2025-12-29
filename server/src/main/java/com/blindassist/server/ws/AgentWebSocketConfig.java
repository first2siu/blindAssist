package com.blindassist.server.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentHandler;

    public AgentWebSocketConfig(AgentWebSocketHandler agentHandler) {
        this.agentHandler = agentHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // App 将连接 ws://YOUR_IP:8080/ws/agent
        registry.addHandler(agentHandler, "/ws/agent").setAllowedOrigins("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 设置文本缓冲区大小 (例如 10MB)，足够容纳 Base64 图片
        container.setMaxTextMessageBufferSize(10 * 1024 * 1024);
        // 设置二进制缓冲区大小
        container.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        return container;
    }
}