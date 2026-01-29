package com.blindassist.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FastAPI服务配置属性
 */
@Configuration
@ConfigurationProperties(prefix = "fastapi")
public class FastApiProperties {

    private Service intentClassifier;
    private Service autoglm;
    private Service obstacle;
    private String websocketBaseUrl;
    private String websocketNavigationBaseUrl;

    public static class Service {
        private String baseUrl;
        private String model;
        private int timeout = 10000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
    }

    public Service getIntentClassifier() { return intentClassifier; }
    public void setIntentClassifier(Service intentClassifier) { this.intentClassifier = intentClassifier; }

    public Service getAutoglm() { return autoglm; }
    public void setAutoglm(Service autoglm) { this.autoglm = autoglm; }

    public Service getObstacle() { return obstacle; }
    public void setObstacle(Service obstacle) { this.obstacle = obstacle; }

    public String getWebsocketBaseUrl() { return websocketBaseUrl; }
    public void setWebsocketBaseUrl(String websocketBaseUrl) { this.websocketBaseUrl = websocketBaseUrl; }

    public String getWebsocketNavigationBaseUrl() { return websocketNavigationBaseUrl; }
    public void setWebsocketNavigationBaseUrl(String websocketNavigationBaseUrl) {
        this.websocketNavigationBaseUrl = websocketNavigationBaseUrl;
    }
}
