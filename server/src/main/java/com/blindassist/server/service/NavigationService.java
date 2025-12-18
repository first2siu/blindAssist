package com.blindassist.server.service;

import com.blindassist.server.api.dto.NavigationRouteRequest;
import com.blindassist.server.api.dto.NavigationRouteResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 导航服务：
 * - 当前为示例逻辑：根据经纬度简单构造三步导航语音
 * - 实际项目中可在此处对接高德地图 MCP 或 AutoGLM + 手机地图
 */
@Service
public class NavigationService {

    public NavigationRouteResponse planRoute(NavigationRouteRequest req) {
        List<String> steps = new ArrayList<>();
        String start = "(" + req.getStartLat() + "," + req.getStartLng() + ")";
        String end = "(" + req.getEndLat() + "," + req.getEndLng() + ")";
        steps.add("已为您规划从 " + start + " 到 " + end + " 的路线。");
        steps.add("请沿当前人行道直行大约 100 米，注意左侧盲道是否被占用。");
        steps.add("前方十字路口，等待红绿灯变为绿灯后继续直行。");

        NavigationRouteResponse resp = new NavigationRouteResponse();
        resp.setVoiceSteps(steps);
        return resp;
    }
}


