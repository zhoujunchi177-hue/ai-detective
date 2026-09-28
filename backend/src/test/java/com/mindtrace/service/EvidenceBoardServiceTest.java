package com.mindtrace.service;

import com.mindtrace.dto.EvidenceDtos;
import com.mindtrace.entity.Clue;
import com.mindtrace.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据板的校验规则测试。
 * <p>
 * 后端只负责「两端线索都属于本案且已被该玩家发现」，**不判断关联是否成立**——
 * 那是玩家推理的内容。所以这里断言的都是归属与格式规则，不是推理结论。
 */
class EvidenceBoardServiceTest {

    private static final Long CASE_ID = 1L;

    // ------------------------------------------------------------------
    // 关系类型
    // ------------------------------------------------------------------

    @Test
    @DisplayName("关系类型留空时回退为默认值")
    void blankRelationTypeFallsBackToDefault() {
        assertEquals(EvidenceBoardService.DEFAULT_RELATION_TYPE,
                EvidenceBoardService.normalizeRelationType(null));
        assertEquals(EvidenceBoardService.DEFAULT_RELATION_TYPE,
                EvidenceBoardService.normalizeRelationType("   "));
    }

    @Test
    @DisplayName("关系类型大小写不敏感，统一归一化为大写")
    void relationTypeIsNormalizedToUpperCase() {
        assertEquals("CONTRADICTS", EvidenceBoardService.normalizeRelationType(" contradicts "));
        assertEquals("TIMELINE", EvidenceBoardService.normalizeRelationType("timeline"));
    }

    @Test
    @DisplayName("未知关系类型直接拒绝，不静默改写成默认值")
    void unknownRelationTypeIsRejected() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> EvidenceBoardService.normalizeRelationType("CAUSES"));
        assertTrue(error.getMessage().contains("CAUSES"));
    }

    @Test
    @DisplayName("每个关系类型都能拿到中文标签；未知类型原样返回")
    void relationLabelIsResolvable() {
        for (EvidenceDtos.RelationTypeOption option : EvidenceBoardService.RELATION_TYPES) {
            assertEquals(option.label(), EvidenceBoardService.relationLabel(option.value()));
        }
        assertEquals("SOMETHING", EvidenceBoardService.relationLabel("SOMETHING"));
    }

    // ------------------------------------------------------------------
    // 备注
    // ------------------------------------------------------------------

    @Test
    @DisplayName("备注去空白，空备注存 null 而不是空串")
    void noteIsTrimmedAndBlankBecomesNull() {
        assertEquals("时间码冲突", EvidenceBoardService.normalizeNote("  时间码冲突  "));
        assertNull(EvidenceBoardService.normalizeNote("   "));
        assertNull(EvidenceBoardService.normalizeNote(null));
    }

    @Test
    @DisplayName("备注超长直接报错，不静默截断玩家写的内容")
    void overlongNoteIsRejected() {
        String tooLong = "线".repeat(EvidenceBoardService.MAX_NOTE_LENGTH + 1);
        assertThrows(BusinessException.class, () -> EvidenceBoardService.normalizeNote(tooLong));

        String atLimit = "线".repeat(EvidenceBoardService.MAX_NOTE_LENGTH);
        assertEquals(atLimit, EvidenceBoardService.normalizeNote(atLimit));
    }

    // ------------------------------------------------------------------
    // 连线两端
    // ------------------------------------------------------------------

    @Test
    @DisplayName("缺少一端或两端相同时拒绝")
    void pairMustHaveTwoDifferentClues() {
        assertThrows(BusinessException.class, () -> EvidenceBoardService.requireValidPair(null, 2L));
        assertThrows(BusinessException.class, () -> EvidenceBoardService.requireValidPair(1L, null));
        assertThrows(BusinessException.class, () -> EvidenceBoardService.requireValidPair(3L, 3L));

        assertDoesNotThrow(() -> EvidenceBoardService.requireValidPair(1L, 2L));
    }

    @Test
    @DisplayName("线索数量不足两条时按「线索不存在」处理")
    void pairMustResolveToTwoClues() {
        EvidenceDtos.CreateEvidenceLinkRequest request =
                new EvidenceDtos.CreateEvidenceLinkRequest(1L, 2L, "SUPPORTS", null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> EvidenceBoardService.requireSameCaseAndDiscovered(
                        CASE_ID, Set.of(1L, 2L), List.of(clue(1L, CASE_ID)), request));
        assertEquals(404, error.getStatus());
    }

    @Test
    @DisplayName("跨案件连线被拒绝")
    void crossCaseLinkIsRejected() {
        EvidenceDtos.CreateEvidenceLinkRequest request =
                new EvidenceDtos.CreateEvidenceLinkRequest(1L, 20L, "SUPPORTS", null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> EvidenceBoardService.requireSameCaseAndDiscovered(
                        CASE_ID, Set.of(1L, 20L), List.of(clue(1L, CASE_ID), clue(20L, 2L)), request));
        assertTrue(error.getMessage().contains("不属于当前案件"));
    }

    @Test
    @DisplayName("只能关联玩家自己已发现的线索")
    void undiscoveredClueIsRejected() {
        EvidenceDtos.CreateEvidenceLinkRequest request =
                new EvidenceDtos.CreateEvidenceLinkRequest(1L, 2L, "SUPPORTS", null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> EvidenceBoardService.requireSameCaseAndDiscovered(
                        CASE_ID, Set.of(1L), List.of(clue(1L, CASE_ID), clue(2L, CASE_ID)), request));
        assertTrue(error.getMessage().contains("已发现"));
    }

    @Test
    @DisplayName("两条线索都属于本案且都已发现时通过")
    void validPairPasses() {
        EvidenceDtos.CreateEvidenceLinkRequest request =
                new EvidenceDtos.CreateEvidenceLinkRequest(1L, 2L, "SUPPORTS", null);

        assertDoesNotThrow(() -> EvidenceBoardService.requireSameCaseAndDiscovered(
                CASE_ID, Set.of(1L, 2L), List.of(clue(1L, CASE_ID), clue(2L, CASE_ID)), request));
    }

    @Test
    @DisplayName("起点线索未发现时同样被拒绝（不能只看终点那一条）")
    void undiscoveredFromClueIsRejected() {
        // 上面那条测的是「终点未发现」，这里补「起点未发现」——
        // 校验是 from || to 两个析取，只测一侧会漏掉另一侧。
        EvidenceDtos.CreateEvidenceLinkRequest request =
                new EvidenceDtos.CreateEvidenceLinkRequest(1L, 2L, "SUPPORTS", null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> EvidenceBoardService.requireSameCaseAndDiscovered(
                        CASE_ID, Set.of(2L), List.of(clue(1L, CASE_ID), clue(2L, CASE_ID)), request));
        assertTrue(error.getMessage().contains("已发现"));
    }

    private Clue clue(Long id, Long caseId) {
        Clue clue = new Clue();
        clue.setId(id);
        clue.setCaseId(caseId);
        clue.setClueCode("CLUE-" + id);
        return clue;
    }
}
