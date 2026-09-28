package com.mindtrace.controller;

import com.mindtrace.common.ApiResponse;
import com.mindtrace.dto.RankingDtos;
import com.mindtrace.security.UserContext;
import com.mindtrace.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ranking")
@RequiredArgsConstructor
public class RankingController {
    private final LeaderboardService leaderboardService;

    @GetMapping
    public ApiResponse<RankingDtos.RankingBoard> ranking(
            @RequestParam(defaultValue = "score") String type) {
        return ApiResponse.ok(leaderboardService.ranking(type, UserContext.currentUserIdOrNull()));
    }
}

