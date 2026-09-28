package com.mindtrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindtrace.agent.AgentAnswer;
import com.mindtrace.agent.AgentService;
import com.mindtrace.dto.AgentDtos;
import com.mindtrace.entity.InvestigationRecord;
import com.mindtrace.mapper.InvestigationRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReasoningService {
    private final AgentService agentService;
    private final CaseQueryService caseQueryService;
    private final InvestigationRecordMapper investigationRecordMapper;

    /**
     * 刻意**不加** {@code @Transactional}：AI 推理要几秒到几十秒，
     * 圈进事务会让数据库连接一直被占用（Hikari 默认池只有 10 个连接）。
     * <p>
     * 整个方法只有末尾一条 insert，本来也不需要事务 ——
     * 而且记录本身是「假设 + AI 分析结果」的整体，分析没完成就不该落库，
     * 所以 insert 放在 AI 调用之后是刻意的。
     */
    public AgentDtos.ReasoningResult analyze(Long caseId, Long userId,
                                             AgentDtos.ReasoningRequest request) {
        caseQueryService.requireCase(caseId);
        AgentAnswer answer = agentService.reasoning(userId, caseId, request.hypothesis());
        JsonNode json = agentService.parseOrNull(answer.content());

        String summary = text(json, "summary",
                "当前回答未能完整解析，但推理内容已保存，可继续补充已发现线索。");
        List<String> supporting = array(json, "supportingEvidence");
        List<String> contradictions = array(json, "contradictions");
        List<String> missing = array(json, "missingEvidence");
        List<String> suggestions = array(json, "suggestions");
        int confidence = json == null ? 0 : clampConfidence(json.path("confidence").asInt(0));
        String confidenceLabel = confidenceLabel(confidence);

        InvestigationRecord record = new InvestigationRecord();
        record.setUserId(userId);
        record.setCaseId(caseId);
        record.setActionType("REASONING");
        record.setResultText(request.hypothesis() + "\n\n" + answer.content());
        record.setCreatedAt(LocalDateTime.now());
        investigationRecordMapper.insert(record);

        return new AgentDtos.ReasoningResult(
                summary,
                supporting,
                contradictions,
                missing,
                suggestions,
                confidence,
                confidenceLabel,
                answer.aiAvailable(),
                answer.notice());
    }

    // confidence 只表示“当前游戏推理与已获得线索的一致程度”，不代表现实案件中的犯罪概率。
    static int clampConfidence(int value) {
        return Math.max(0, Math.min(100, value));
    }

    static String confidenceLabel(int confidence) {
        return confidence >= 75 ? "与现有线索高度一致"
                : confidence >= 45 ? "部分一致，仍需验证" : "证据不足，仍是开放假设";
    }

    private String text(JsonNode json, String field, String fallback) {
        if (json == null || !json.path(field).isTextual() || json.path(field).asText().isBlank()) {
            return fallback;
        }
        return json.path(field).asText();
    }

    private List<String> array(JsonNode json, String field) {
        List<String> result = new ArrayList<>();
        if (json == null || !json.path(field).isArray()) {
            return result;
        }
        for (JsonNode node : json.path(field)) {
            if (node.isTextual() && !node.asText().isBlank()) {
                result.add(node.asText());
            }
        }
        return result;
    }
}

