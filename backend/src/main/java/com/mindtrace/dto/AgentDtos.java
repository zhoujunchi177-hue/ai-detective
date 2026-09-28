package com.mindtrace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AgentDtos {

    private AgentDtos() {
    }

    public record ChatRequest(
            @NotNull(message = "请选择 NPC") Long npcId,
            @NotBlank(message = "问题不能为空")
            @Size(max = 1000, message = "单次问题最多 1000 字")
            String message) {
    }

    public record ChatResponse(
            String reply,
            boolean aiAvailable,
            String aiNotice,
            List<Long> unlockedClueIds) {
    }

    public record ReasoningRequest(
            @NotBlank(message = "推理内容不能为空")
            @Size(max = 5000, message = "推理内容最多 5000 字")
            String hypothesis) {
    }

    public record ReasoningResult(
            String summary,
            List<String> supportingEvidence,
            List<String> contradictions,
            List<String> missingEvidence,
            List<String> suggestions,
            int confidence,
            String confidenceLabel,
            boolean aiAvailable,
            String aiNotice) {
    }

    public record SubmitRequest(
            @NotBlank(message = "核心假设不能为空") String hypothesis,
            @NotBlank(message = "关键人物不能为空") String keyPeople,
            @NotBlank(message = "关键时间线不能为空") String keyTimeline,
            List<Long> evidenceClueIds,
            @NotBlank(message = "推理过程不能为空") String reasoningText,
            @NotBlank(message = "最终结论不能为空") String conclusion) {
    }

    public record SubmitResult(
            Long recordId,
            int investigationScore,
            int clueScore,
            int timelineScore,
            int logicScore,
            int totalScore,
            int expReward,
            int coinReward,
            String rating,
            String aiReport,
            boolean aiAvailable,
            String aiNotice,
            List<String> newAchievements) {
    }
}

