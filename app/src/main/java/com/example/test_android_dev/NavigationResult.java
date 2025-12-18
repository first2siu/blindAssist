package com.example.test_android_dev;

import java.util.List;

/**
 * 导航结果数据结构：
 * - 由服务端返回，可结合高德地图或手机地图软件的路线信息
 * - 客户端可只用来做语音播报与避障联动
 */
public class NavigationResult {

    public List<String> voiceSteps; // 每一步的语音提示文本

    // TODO: 可扩展为包含经纬度轨迹、路口信息、红绿灯位置等
}


