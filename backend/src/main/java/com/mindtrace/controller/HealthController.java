package com.mindtrace.controller;

import com.mindtrace.common.ApiResponse;
import com.mindtrace.deepseek.DeepSeekService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {
    private final DeepSeekService deepSeekService;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "deepSeekConfigured", deepSeekService.isConfigured()));
    }
}
