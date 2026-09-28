package com.mindtrace.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 推理 confidence 的取值范围与文案分级测试。
 * confidence 表示“当前游戏推理与已获得线索的一致程度”，必须落在 0-100，
 * 且不能被误解为现实案件中的犯罪概率。
 */
class ReasoningServiceTest {

    @Test
    @DisplayName("confidence 被限制在 0-100")
    void confidenceIsClampedIntoRange() {
        assertEquals(0, ReasoningService.clampConfidence(-10));
        assertEquals(0, ReasoningService.clampConfidence(0));
        assertEquals(58, ReasoningService.clampConfidence(58));
        assertEquals(100, ReasoningService.clampConfidence(100));
        assertEquals(100, ReasoningService.clampConfidence(150));
    }

    @Test
    @DisplayName("文案分级边界正确")
    void confidenceLabelMatchesBands() {
        assertEquals("与现有线索高度一致", ReasoningService.confidenceLabel(100));
        assertEquals("与现有线索高度一致", ReasoningService.confidenceLabel(75));
        assertEquals("部分一致，仍需验证", ReasoningService.confidenceLabel(74));
        assertEquals("部分一致，仍需验证", ReasoningService.confidenceLabel(45));
        assertEquals("证据不足，仍是开放假设", ReasoningService.confidenceLabel(44));
        assertEquals("证据不足，仍是开放假设", ReasoningService.confidenceLabel(0));
    }

    @Test
    @DisplayName("文案不包含对现实案件下结论的措辞")
    void labelDoesNotClaimRealWorldConclusion() {
        String label = ReasoningService.confidenceLabel(100);
        assertEquals(-1, label.indexOf("凶手"));
        assertEquals(-1, label.indexOf("真凶"));
    }
}
