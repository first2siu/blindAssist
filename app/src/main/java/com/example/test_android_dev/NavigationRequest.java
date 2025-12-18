package com.example.test_android_dev;

/**
 * 导航请求参数：
 * - 可由客户端构造（起点为当前位置，终点为用户语音描述解析结果）
 * - 也可以全部交给后端解析语音并构造
 */
public class NavigationRequest {

    public double startLat;
    public double startLng;
    public double endLat;
    public double endLng;

    public String description; // 用户语音描述原文，可选

    public NavigationRequest(double startLat, double startLng,
                             double endLat, double endLng) {
        this.startLat = startLat;
        this.startLng = startLng;
        this.endLat = endLat;
        this.endLng = endLng;
    }
}


