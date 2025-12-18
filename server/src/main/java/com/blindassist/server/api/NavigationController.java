package com.blindassist.server.api;

import com.blindassist.server.api.dto.NavigationRouteRequest;
import com.blindassist.server.api.dto.NavigationRouteResponse;
import com.blindassist.server.service.NavigationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导航接口：
 * - 客户端提供起终点坐标（或由后端依据语音再解析）
 * - 返回一系列可以直接语音播报的导航步骤
 */
@RestController
@RequestMapping("/api/navigation")
public class NavigationController {

    private final NavigationService navigationService;

    public NavigationController(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @PostMapping("/route")
    public NavigationRouteResponse planRoute(@RequestBody NavigationRouteRequest req) {
        return navigationService.planRoute(req);
    }
}


