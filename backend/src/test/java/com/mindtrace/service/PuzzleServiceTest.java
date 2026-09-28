package com.mindtrace.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 谜题答案比对逻辑测试。
 * 这些断言直接调用生产代码 {@link PuzzleService#canonical}，而不是在测试里重写一遍逻辑，
 * 否则测试通过并不能说明线上行为正确。
 */
class PuzzleServiceTest {

    @Test
    @DisplayName("EVIDENCE_LINK 类型与选择顺序无关")
    void evidenceLinkIgnoresOrder() {
        String expected = PuzzleService.canonical("EVIDENCE_LINK",
                "maintenance,guest-report,rooftop-tank");
        String actual = PuzzleService.canonical("EVIDENCE_LINK",
                "rooftop-tank,maintenance,guest-report");
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("EVIDENCE_LINK 忽略大小写、空格与多余逗号")
    void evidenceLinkIgnoresCaseSpaceAndBlankItems() {
        assertEquals(
                PuzzleService.canonical("EVIDENCE_LINK", "A,b"),
                PuzzleService.canonical("EVIDENCE_LINK", " b ,, A ,"));
    }

    @Test
    @DisplayName("TIME_SORT / PERSON_RELATION 顺序敏感，不能误判为相同")
    void orderSensitiveTypesKeepOrder() {
        assertNotEquals(
                PuzzleService.canonical("TIME_SORT", "a,b"),
                PuzzleService.canonical("TIME_SORT", "b,a"));
    }

    @Test
    @DisplayName("null 与空白答案归一化为空串，不抛异常")
    void nullAndBlankAnswersAreSafe() {
        assertEquals("", PuzzleService.canonical("TIME_SORT", null));
        assertEquals("", PuzzleService.normalize(null));
        assertEquals("", PuzzleService.canonical("EVIDENCE_LINK", "  ,  "));
    }
}
