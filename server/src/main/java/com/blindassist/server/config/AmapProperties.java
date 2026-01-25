package com.blindassist.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 高德地图配置属性
 */
@Configuration
@ConfigurationProperties(prefix = "amap")
public class AmapProperties {

    private String apiKey;
    private String webApiKey;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getWebApiKey() { return webApiKey; }
    public void setWebApiKey(String webApiKey) { this.webApiKey = webApiKey; }
}
