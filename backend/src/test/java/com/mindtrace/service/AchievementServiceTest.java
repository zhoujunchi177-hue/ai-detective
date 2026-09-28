package com.mindtrace.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成就解锁条件与稀有度的判定测试。
 * <p>
 * 条件判定被抽成 {@link AchievementService#shouldUnlock} 这样的纯函数，
 * 就是为了能零 mock 直接测 —— Java 25 + byte-buddy 1.15.11 下 Mockito 的 inline mock 不可用，
 * 一旦逻辑写在 service 方法体里就必须靠真实数据库才能覆盖。
 */
class AchievementServiceTest {

    private static AchievementService.AchievementFacts facts(
            int completedCases, long hiddenClues, long contradictionClues, long solvedTimeSortPuzzles) {
        return new AchievementService.AchievementFacts(
                completedCases, hiddenClues, contradictionClues, solvedTimeSortPuzzles);
    }

    private static final AchievementService.AchievementFacts NOTHING_PLAYED = facts(0, 0, 0, 0);

    @Test
    @DisplayName("什么都没玩时，四枚徽章都不解锁")
    void nothingUnlocksOnAFreshAccount() {
        for (String code : new String[]{"FIRST_CASE", "FIRST_HIDDEN", "KEY_CONTRADICTION", "TIMELINE_MASTER"}) {
            assertFalse(AchievementService.shouldUnlock(code, NOTHING_PLAYED), code);
        }
    }

    @Test
    @DisplayName("未知 code 一律不解锁（数据库新增徽章但没写条件时不会白送）")
    void unknownCodeNeverUnlocks() {
        assertFalse(AchievementService.shouldUnlock("SOME_FUTURE_BADGE", facts(99, 99, 99, 99)));
    }

    @Test
    @DisplayName("FIRST_CASE 只看结案数")
    void firstCaseOnlyLooksAtCompletedCases() {
        assertTrue(AchievementService.shouldUnlock("FIRST_CASE", facts(1, 0, 0, 0)));
        assertFalse(AchievementService.shouldUnlock("FIRST_CASE", facts(0, 5, 5, 5)));
    }

    @Test
    @DisplayName("FIRST_HIDDEN / KEY_CONTRADICTION 各自只看对应类型的线索")
    void clueBadgesAreNotInterchangeable() {
        assertTrue(AchievementService.shouldUnlock("FIRST_HIDDEN", facts(0, 1, 0, 0)));
        assertFalse(AchievementService.shouldUnlock("FIRST_HIDDEN", facts(0, 0, 3, 0)));

        assertTrue(AchievementService.shouldUnlock("KEY_CONTRADICTION", facts(0, 0, 1, 0)));
        assertFalse(AchievementService.shouldUnlock("KEY_CONTRADICTION", facts(0, 3, 0, 0)));
    }

    @Test
    @DisplayName("TIMELINE_MASTER 要求真的完成过时间线排序谜题，不能只靠结案")
    void timelineMasterRequiresTheTimelinePuzzle() {
        // 这就是修复的那个缺陷：结案但没解谜题时，不该再白送这枚徽章
        assertFalse(AchievementService.shouldUnlock("TIMELINE_MASTER", facts(1, 0, 0, 0)));
        assertTrue(AchievementService.shouldUnlock("TIMELINE_MASTER", facts(0, 0, 0, 1)));
    }

    @Test
    @DisplayName("四枚徽章的条件两两不同 —— 没有任何两枚会在同一次事件里一起解锁")
    void everyBadgeHasADistinctCondition() {
        // 每个「只满足一个条件」的事实组合，都应当**只**解锁一枚徽章。
        // 若两枚徽章条件重复，某个组合就会同时命中两枚，这条断言随即失败。
        AchievementService.AchievementFacts[] singleConditionFacts = {
                facts(1, 0, 0, 0),
                facts(0, 1, 0, 0),
                facts(0, 0, 1, 0),
                facts(0, 0, 0, 1),
        };
        String[] codes = {"FIRST_CASE", "FIRST_HIDDEN", "KEY_CONTRADICTION", "TIMELINE_MASTER"};

        for (AchievementService.AchievementFacts single : singleConditionFacts) {
            int unlocked = 0;
            for (String code : codes) {
                if (AchievementService.shouldUnlock(code, single)) {
                    unlocked += 1;
                }
            }
            assertEquals(1, unlocked, "每个单条件组合应恰好解锁一枚，实际 " + unlocked + " 枚");
        }
    }

    @Test
    @DisplayName("稀有度兜底：NULL / 空串 / 未知值都归到 COMMON")
    void rarityFallsBackToCommon() {
        assertEquals("COMMON", AchievementService.normalizeRarity(null));
        assertEquals("COMMON", AchievementService.normalizeRarity(""));
        assertEquals("COMMON", AchievementService.normalizeRarity("   "));
        assertEquals("COMMON", AchievementService.normalizeRarity("MYTHIC"));
    }

    @Test
    @DisplayName("稀有度大小写与空格都能归一")
    void rarityIsNormalized() {
        assertEquals("LEGENDARY", AchievementService.normalizeRarity("legendary"));
        assertEquals("EPIC", AchievementService.normalizeRarity(" Epic "));
        assertEquals("RARE", AchievementService.normalizeRarity("RARE"));
    }
}
