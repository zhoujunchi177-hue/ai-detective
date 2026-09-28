package com.mindtrace.controller;

import com.mindtrace.common.ApiResponse;
import com.mindtrace.dto.AgentDtos;
import com.mindtrace.security.UserContext;
import com.mindtrace.service.ChatService;
import com.mindtrace.service.GameService;
import com.mindtrace.service.ReasoningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class AgentController {
    private final ChatService chatService;
    private final ReasoningService reasoningService;
    private final GameService gameService;

    @GetMapping("/{id}/chat")
    public ApiResponse<java.util.List<com.mindtrace.dto.CaseDtos.ChatView>> history(
            @PathVariable Long id, @RequestParam Long npcId) {
        return ApiResponse.ok(chatService.history(id, UserContext.userId(), npcId));
    }

    @PostMapping("/{id}/chat")
    public ApiResponse<AgentDtos.ChatResponse> chat(
            @PathVariable Long id,
            @Valid @RequestBody AgentDtos.ChatRequest request) {
        return ApiResponse.ok(chatService.chat(id, UserContext.userId(), request));
    }

    @PostMapping("/{id}/reasoning")
    public ApiResponse<AgentDtos.ReasoningResult> reasoning(
            @PathVariable Long id,
            @Valid @RequestBody AgentDtos.ReasoningRequest request) {
        return ApiResponse.ok(reasoningService.analyze(id, UserContext.userId(), request));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<AgentDtos.SubmitResult> submit(
            @PathVariable Long id,
            @Valid @RequestBody AgentDtos.SubmitRequest request) {
        return ApiResponse.ok("案件提交成功", gameService.submit(id, UserContext.userId(), request));
    }
}
