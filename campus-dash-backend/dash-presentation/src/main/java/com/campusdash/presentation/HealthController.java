package com.campusdash.presentation;

import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 健康检查：前端用它探测后端，不走认证 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of("status", "UP", "project", "campus-dash"));
    }
}
