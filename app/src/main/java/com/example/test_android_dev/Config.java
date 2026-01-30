package com.example.test_android_dev;

/**
 * App configuration and feature flags.
 */
public class Config {

    /**
     * Master switch for mock mode.
     * If true, the app will not make real network calls but will use fake data generators.
     * This is useful for testing UI and logic flows without a backend.
     * Set to false to connect to a real backend server.
     */
    public static final boolean MOCK_MODE = false; // Set to false for real backend testing

    /**
     * Debug mode switch for UI.
     * If true, shows the developer test interface (manual text input).
     * If false, shows the user voice interface (press-to-talk button).
     * Set to false for production builds.
     */
    public static final boolean DEBUG_MODE = false; // Set to true for development testing
    
    // ==================== 讯飞语音识别配置 ====================
    // 在讯飞开放平台注册获取: https://www.xfyun.cn/
    // 创建应用后，在控制台获取以下三个参数
    // 注意：这些是敏感信息，生产环境建议从服务器获取或使用更安全的存储方式
    
    /**
     * 讯飞 APPID
     * 在讯飞开放平台创建应用后获取
     */
    public static final String XUNFEI_APP_ID = "";
    
    /**
     * 讯飞 APIKey
     * 在讯飞开放平台应用管理中获取
     */
    public static final String XUNFEI_API_KEY = "";
    
    /**
     * 讯飞 APISecret
     * 在讯飞开放平台应用管理中获取
     */
    public static final String XUNFEI_API_SECRET = "";

    // ==================== 服务器地址配置 ====================

    /**
     * Spring Boot 服务器地址
     * 用于 TTS 队列轮询和其他 REST API
     */
    public static final String SPRING_BOOT_BASE_URL = "http://10.181.78.161:8090";

    /**
     * TTS 轮询 API 端点
     */
    public static final String TTS_PULL_URL = SPRING_BOOT_BASE_URL + "/api/tts/pull";

    /**
     * TTS 恢复打断消息 API 端点
     */
    public static final String TTS_RESUME_URL = SPRING_BOOT_BASE_URL + "/api/tts/resume";

    /**
     * TTS 保存打断消息 API 端点
     */
    public static final String TTS_INTERRUPT_URL = SPRING_BOOT_BASE_URL + "/api/tts/interrupt";

    /**
     * 用户 ID（用于 TTS 队列识别）
     * 生产环境应使用唯一设备标识或登录用户 ID
     */
    public static final String USER_ID = "android_user_default";
}
