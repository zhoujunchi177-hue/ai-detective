package com.mindtrace.service;

import com.mindtrace.dto.CaseDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调查日志的展示文本处理测试。
 * <p>
 * REASONING 记录在数据库里存的是「玩家假设 + 原始 AI JSON」，原始 JSON 用于审计，
 * 但玩家界面只能看到假设本身，不能把 AI 原始输出泄露到前端。
 * <p>
 * 同时覆盖调查地图节点状态的判定：节点状态必须由后端计算，前端不得自行推断。
 */
class CaseQueryServiceTest {

    private static final String RAW_JSON =
            "{\"summary\":\"内部审阅结论\",\"confidence\":25}";

    private static final Map<String, String> LOCATION_NAMES = Map.of(
            "lobby", "酒店大厅与前台",
            "water-system", "供水系统与投诉记录");

    @Test
    @DisplayName("推理记录只保留玩家假设，剥离原始 AI JSON")
    void reasoningTextStripsRawAiJson() {
        String stored = "电梯监控缺失，可能有人处理过录像。\n\n" + RAW_JSON;

        String shown = CaseQueryService.displayText("REASONING", stored);

        assertEquals("电梯监控缺失，可能有人处理过录像。", shown);
        assertEquals(-1, shown.indexOf("summary"));
        assertEquals(-1, shown.indexOf("{"));
    }

    @Test
    @DisplayName("没有 JSON 尾巴时原样返回")
    void reasoningTextWithoutJsonIsUnchanged() {
        String stored = "这是一条降级推理，没有 AI 原始输出。";

        assertEquals(stored, CaseQueryService.displayText("REASONING", stored));
    }

    @Test
    @DisplayName("假设本身包含花括号时不会被误截断")
    void reasoningTextKeepsBracesInsideHypothesis() {
        String stored = "假设 {A} 与 {B} 同时成立。\n\n" + RAW_JSON;

        assertEquals("假设 {A} 与 {B} 同时成立。", CaseQueryService.displayText("REASONING", stored));
    }

    @Test
    @DisplayName("地点调查等其他类型不受影响")
    void otherActionTypesAreUnchanged() {
        String stored = "你在酒店大厅与前台完成检查，发现 3 条新线索。";

        assertEquals(stored, CaseQueryService.displayText("SEARCH", stored));
        assertEquals(stored, CaseQueryService.displayText("PUZZLE", stored));
        assertEquals(stored, CaseQueryService.displayText(null, stored));
    }

    @Test
    @DisplayName("resultText 为空时安全返回 null")
    void nullResultTextIsSafe() {
        assertNull(CaseQueryService.displayText("REASONING", null));
    }

    // ------------------------------------------------------------------
    // 节点解锁条件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("public 与空条件始终开放")
    void publicConditionIsAlwaysOpen() {
        assertNull(unlockReason("public", Set.of(), Set.of(), Set.of()));
        assertNull(unlockReason("PUBLIC", Set.of(), Set.of(), Set.of()));
        assertNull(unlockReason("*", Set.of(), Set.of(), Set.of()));
        assertNull(unlockReason("", Set.of(), Set.of(), Set.of()));
        assertNull(unlockReason(null, Set.of(), Set.of(), Set.of()));
    }

    @Test
    @DisplayName("线索前置：未发现时说明需要哪条线索，发现后开放")
    void clueConditionDependsOnDiscoveredClue() {
        String locked = unlockReason("clue:CLUE-006", Set.of(), Set.of(), Set.of());
        assertEquals("需要先发现线索 CLUE-006", locked);

        assertNull(unlockReason("clue:CLUE-006", Set.of("CLUE-006"), Set.of(), Set.of()));
    }

    @Test
    @DisplayName("谜题前置：按谜题 ID 判定，未破解时给出提示")
    void puzzleConditionDependsOnSolvedPuzzle() {
        assertTrue(unlockReason("puzzle:1", Set.of(), Set.of(), Set.of()).contains("谜题"));

        assertNull(unlockReason("puzzle:1", Set.of(), Set.of(1L), Set.of()));
    }

    @Test
    @DisplayName("地点前置：未调查时用可读的地点名说明")
    void locationConditionUsesReadableName() {
        String locked = unlockReason("location:lobby", Set.of(), Set.of(), Set.of());
        assertEquals("需要先调查「酒店大厅与前台」", locked);

        assertNull(unlockReason("location:lobby", Set.of(), Set.of(), Set.of("lobby")));
    }

    @Test
    @DisplayName("地点前置遇到未知 key 时回退为原始 key，不会崩")
    void locationConditionFallsBackToRawKey() {
        String locked = unlockReason("location:nowhere", Set.of(), Set.of(), Set.of());

        assertTrue(locked.contains("nowhere"));
    }

    @Test
    @DisplayName("多个前置条件用逗号分隔，必须全部满足")
    void multipleConditionsRequireAll() {
        Set<String> clues = Set.of("CLUE-002");
        Set<Long> puzzles = Set.of(1L);

        String partial = unlockReason("clue:CLUE-002,puzzle:1,location:lobby",
                clues, puzzles, Set.of());
        assertEquals("需要先调查「酒店大厅与前台」", partial);

        assertNull(unlockReason("clue:CLUE-002,puzzle:1,location:lobby",
                clues, puzzles, Set.of("lobby")));
    }

    @Test
    @DisplayName("未知前置类型保守判为未开放，不当作开放节点")
    void unknownConditionKindStaysLocked() {
        String locked = unlockReason("teapot:42", Set.of(), Set.of(), Set.of());

        assertEquals("该地点尚未开放", locked);
    }

    // ------------------------------------------------------------------
    // 节点状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有未满足前置条件时一律是 LOCKED")
    void lockedWinsOverEverything() {
        assertEquals("LOCKED", CaseQueryService.nodeStatus("需要先发现线索 CLUE-006", true, 2, 2));
        assertEquals("LOCKED", CaseQueryService.nodeStatus("需要先发现线索 CLUE-006", false, 0, 2));
    }

    @Test
    @DisplayName("已解锁但没调查过是 AVAILABLE")
    void unlockedButNotInvestigatedIsAvailable() {
        assertEquals("AVAILABLE", CaseQueryService.nodeStatus(null, false, 0, 2));
    }

    @Test
    @DisplayName("调查过但线索没找全是 INVESTIGATED")
    void partiallyInvestigatedIsInvestigated() {
        assertEquals("INVESTIGATED", CaseQueryService.nodeStatus(null, true, 1, 2));
    }

    @Test
    @DisplayName("调查过且线索找全是 COMPLETED（没有线索的地点也算完成）")
    void fullyInvestigatedIsCompleted() {
        assertEquals("COMPLETED", CaseQueryService.nodeStatus(null, true, 2, 2));
        assertEquals("COMPLETED", CaseQueryService.nodeStatus(null, true, 0, 0));
    }

    // ------------------------------------------------------------------
    // 综合完成度（列表页与详情页必须得到同一个数字）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("综合完成度按 调查30% + 线索30% + 谜题40% 加权")
    void overallPercentIsWeighted() {
        assertEquals(100, CaseQueryService.overallPercent(100, 100, 100));
        assertEquals(0, CaseQueryService.overallPercent(0, 0, 0));
        // 只调查完地点：30 分
        assertEquals(30, CaseQueryService.overallPercent(100, 0, 0));
        // 只找齐线索：30 分
        assertEquals(30, CaseQueryService.overallPercent(0, 100, 0));
        // 只解完全部谜题：40 分
        assertEquals(40, CaseQueryService.overallPercent(0, 0, 100));
    }

    @Test
    @DisplayName("加权结果四舍五入且不会超过 100")
    void overallPercentIsRoundedAndCapped() {
        // 50*0.3 + 50*0.3 + 50*0.4 = 50
        assertEquals(50, CaseQueryService.overallPercent(50, 50, 50));
        // 33*0.3 + 67*0.3 + 100*0.4 = 9.9 + 20.1 + 40 = 70
        assertEquals(70, CaseQueryService.overallPercent(33, 67, 100));
        assertEquals(100, CaseQueryService.overallPercent(120, 120, 120));
    }

    @Test
    @DisplayName("案件没有谜题时谜题维度给满分，否则这类案件永远显示未完成")
    void puzzlePercentGivesFullCreditWhenThereAreNoPuzzles() {
        assertEquals(100, CaseQueryService.puzzlePercent(0, 0));
        assertEquals(0, CaseQueryService.puzzlePercent(0, 3));
        assertEquals(67, CaseQueryService.puzzlePercent(2, 3));
        assertEquals(100, CaseQueryService.puzzlePercent(3, 3));
    }

    // ------------------------------------------------------------------
    // 日志筛选与分页
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日期范围筛选：to 必须包含当天整天，否则「筛选到今天」会漏掉今天的记录")
    void rangeEndCoversTheWholeDay() {
        assertNull(CaseQueryService.rangeStart(null));
        assertNull(CaseQueryService.rangeEnd(null));

        assertEquals(LocalDateTime.of(2026, 9, 20, 0, 0, 0), CaseQueryService.rangeStart(LocalDate.of(2026, 9, 20)));
        // 关键：不是 00:00:00，而是当天最后一刻
        LocalDateTime end = CaseQueryService.rangeEnd(LocalDate.of(2026, 9, 20));
        assertEquals(2026, end.getYear());
        assertEquals(9, end.getMonthValue());
        assertEquals(20, end.getDayOfMonth());
        assertEquals(23, end.getHour());
        assertEquals(59, end.getMinute());
        assertEquals(59, end.getSecond());

        // 当天 12:00 的记录必须落在 [from, to] 里
        LocalDateTime noon = LocalDateTime.of(2026, 9, 20, 12, 0);
        assertTrue(noon.isAfter(CaseQueryService.rangeStart(LocalDate.of(2026, 9, 20))));
        assertTrue(noon.isBefore(end));
    }

    @Test
    @DisplayName("关键词匹配大小写不敏感、忽略首尾空格，空关键词视为全部命中")
    void keywordMatchingIsForgiving() {
        assertTrue(CaseQueryService.matchesKeyword("水泵房的水压异常", null));
        assertTrue(CaseQueryService.matchesKeyword("水泵房的水压异常", "   "));
        assertTrue(CaseQueryService.matchesKeyword("水泵房的水压异常", "水泵"));
        assertTrue(CaseQueryService.matchesKeyword("Pump Room Report", "pump"));
        assertTrue(CaseQueryService.matchesKeyword("水泵房的水压异常", "  水泵  "));
        assertFalse(CaseQueryService.matchesKeyword("水泵房的水压异常", "电梯"));
        // 文本为空时，非空关键词不应命中
        assertFalse(CaseQueryService.matchesKeyword(null, "水泵"));
    }

    @Test
    @DisplayName("类型筛选：ALL / 空值都表示不筛选，其余按类型精确匹配")
    void typeFilterTreatsAllAsNoFilter() {
        List<CaseDtos.HistoryEntry> entries = List.of(
                historyEntry(1L, "SEARCH"),
                historyEntry(2L, "REASONING"),
                historyEntry(3L, "SEARCH"));

        assertEquals(3, CaseQueryService.filterByType(entries, "ALL").size());
        assertEquals(3, CaseQueryService.filterByType(entries, null).size());
        assertEquals(3, CaseQueryService.filterByType(entries, "").size());
        assertEquals(2, CaseQueryService.filterByType(entries, "SEARCH").size());
        assertEquals(1, CaseQueryService.filterByType(entries, "REASONING").size());
        assertEquals(0, CaseQueryService.filterByType(entries, "PUZZLE").size());
        // 大小写不该影响筛选结果
        assertEquals(2, CaseQueryService.filterByType(entries, "search").size());
    }

    @Test
    @DisplayName("类型计数含 ALL 合计，且各类型相加等于 ALL")
    void typeCountsIncludeAllAndAddUp() {
        Map<String, Long> counts = CaseQueryService.countByType(List.of(
                historyEntry(1L, "SEARCH"),
                historyEntry(2L, "REASONING"),
                historyEntry(3L, "SEARCH")));

        assertEquals(3L, counts.get("ALL"));
        assertEquals(2L, counts.get("SEARCH"));
        assertEquals(1L, counts.get("REASONING"));
        long sum = counts.entrySet().stream()
                .filter(entry -> !"ALL".equals(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        assertEquals(counts.get("ALL"), sum);
    }

    @Test
    @DisplayName("分页参数兜底：页码至少为 1，每页条数有默认值也有上限")
    void pagingParamsAreClamped() {
        assertEquals(1, CaseQueryService.normalizePage(0));
        assertEquals(1, CaseQueryService.normalizePage(-5));
        assertEquals(3, CaseQueryService.normalizePage(3));

        assertEquals(20, CaseQueryService.normalizeSize(0));
        assertEquals(20, CaseQueryService.normalizeSize(-1));
        assertEquals(50, CaseQueryService.normalizeSize(50));
        // 上限 100：不能让调用方一次拉走整本日志
        assertEquals(100, CaseQueryService.normalizeSize(5000));
    }

    @Test
    @DisplayName("总页数向上取整；每页条数非法时按 0 页处理，不抛异常")
    void totalPagesRoundsUp() {
        assertEquals(0, CaseQueryService.totalPages(0, 20));
        assertEquals(1, CaseQueryService.totalPages(1, 20));
        assertEquals(1, CaseQueryService.totalPages(20, 20));
        assertEquals(2, CaseQueryService.totalPages(21, 20));
        assertEquals(0, CaseQueryService.totalPages(10, 0));
    }

    @Test
    @DisplayName("type 归一：ALL 与空白都变成 null，否则 SQL 下推会拼出 action_type = 'ALL' 而返回空结果")
    void typeIsNormalizedToNullWhenItMeansNoFilter() {
        assertNull(CaseQueryService.normalizeType(null));
        assertNull(CaseQueryService.normalizeType(""));
        assertNull(CaseQueryService.normalizeType("   "));
        assertNull(CaseQueryService.normalizeType("ALL"));
        // 大小写与前后空格都不该让「全部」变成一个字面量条件
        assertNull(CaseQueryService.normalizeType("all"));
        assertNull(CaseQueryService.normalizeType(" All "));

        assertEquals("SEARCH", CaseQueryService.normalizeType("SEARCH"));
        assertEquals("SEARCH", CaseQueryService.normalizeType("  SEARCH  "));
    }

    @Test
    @DisplayName("type 归一之后：null 走「全部」，归一结果喂给 filterByType 仍与直传 ALL 一致")
    void normalizedTypeFeedsFilterConsistently() {
        List<CaseDtos.HistoryEntry> entries = List.of(
                historyEntry(1L, "SEARCH"), historyEntry(2L, "SEARCH"), historyEntry(3L, "REASONING"));

        // 归一后的 null 与直传 "ALL" 必须等价 —— 这是 SQL 下推与内存过滤共用同一套语义的前提
        assertEquals(
                CaseQueryService.filterByType(entries, "ALL").size(),
                CaseQueryService.filterByType(entries, CaseQueryService.normalizeType("ALL")).size());
        assertEquals(3, CaseQueryService.filterByType(entries, CaseQueryService.normalizeType("ALL")).size());
        assertEquals(2, CaseQueryService.filterByType(entries, CaseQueryService.normalizeType("search")).size());
    }

    @Test
    @DisplayName("分页切片：边界页不重不漏，越界页返回空列表而不是抛异常")
    void paginationSlicesWithoutGapsOrOverlap() {
        List<CaseDtos.HistoryEntry> entries = new ArrayList<>();
        for (long id = 1; id <= 45; id++) {
            entries.add(historyEntry(id, "SEARCH"));
        }

        CaseDtos.HistoryPage first = CaseQueryService.buildPage(entries, Map.of(), 1, 20, false);
        assertEquals(20, first.entries().size());
        assertEquals(1L, first.entries().get(0).id());
        assertEquals(20L, first.entries().get(19).id());
        assertEquals(45, first.total());
        assertEquals(3, first.totalPages());
        assertTrue(first.hasMore());

        CaseDtos.HistoryPage second = CaseQueryService.buildPage(entries, Map.of(), 2, 20, false);
        assertEquals(20, second.entries().size());
        assertEquals(21L, second.entries().get(0).id());
        assertEquals(40L, second.entries().get(19).id());
        assertTrue(second.hasMore());

        CaseDtos.HistoryPage third = CaseQueryService.buildPage(entries, Map.of(), 3, 20, false);
        assertEquals(5, third.entries().size());
        assertEquals(41L, third.entries().get(0).id());
        assertEquals(45L, third.entries().get(4).id());
        assertFalse(third.hasMore());

        // 越界页：空列表 + hasMore=false，不抛 IndexOutOfBounds
        CaseDtos.HistoryPage beyond = CaseQueryService.buildPage(entries, Map.of(), 9, 20, false);
        assertTrue(beyond.entries().isEmpty());
        assertFalse(beyond.hasMore());
    }

    @Test
    @DisplayName("扫描被截断时 truncated 如实上报，界面才不会把下界当成总数")
    void truncatedFlagIsPropagated() {
        List<CaseDtos.HistoryEntry> entries = List.of(historyEntry(1L, "SEARCH"));
        assertTrue(CaseQueryService.buildPage(entries, Map.of(), 1, 20, true).truncated());
        assertFalse(CaseQueryService.buildPage(entries, Map.of(), 1, 20, false).truncated());
    }

    @Test
    @DisplayName("条件串里的空片段被忽略，不影响其余前置的判定")
    void emptyConditionTokenIsIgnored() {
        // 内容维护时很容易写出 "clue:A,,puzzle:1" 或行尾多一个逗号，
        // 这种空片段不能被当成「未知前置」而误判成未开放。
        String reason = unlockReason("clue:CLUE-006,,puzzle:1",
                Set.of("CLUE-006"), Set.of(), Set.of());
        assertEquals("需要先破解谜题 #1", reason);

        assertNull(unlockReason("clue:CLUE-006,", Set.of("CLUE-006"), Set.of(), Set.of()));
        assertNull(unlockReason(" , ,", Set.of(), Set.of(), Set.of()));
    }

    @Test
    @DisplayName("没有冒号的条件片段按未知类型处理，保守判为未开放")
    void conditionWithoutColonIsUnknownKind() {
        // 与 "teapot:42" 不同，这里连冒号都没有 —— 走的是 separator < 0 分支。
        assertEquals("该地点尚未开放", unlockReason("orphan", Set.of(), Set.of(), Set.of()));
        assertEquals("该地点尚未开放", unlockReason("clue:CLUE-006,orphan",
                Set.of("CLUE-006"), Set.of(), Set.of()));
    }

    @Test
    @DisplayName("谜题前置里写了非数字 ID 时保守判为未开放，不因解析失败而放行")
    void nonNumericPuzzleIdStaysLocked() {
        // solvedPuzzleIds 故意非空：若实现漏了 puzzleId == null 这一支，
        // contains(null) 恰好为 false 也可能「碰巧对」，所以要把两侧都钉住。
        String reason = unlockReason("puzzle:abc", Set.of(), Set.of(1L), Set.of());
        assertEquals("需要先破解谜题 #abc", reason);

        assertNull(unlockReason("puzzle:1", Set.of(), Set.of(1L), Set.of()));
    }

    @Test
    @DisplayName("日志类型统计把缺失类型归入 UNKNOWN，而不是丢掉该条")
    void historyTypeCountTreatsNullTypeAsUnknown() {
        List<CaseDtos.HistoryEntry> entries = List.of(
                historyEntry(1L, "SEARCH"),
                historyEntry(2L, null),
                historyEntry(3L, "SEARCH"));

        Map<String, Long> counts = CaseQueryService.countByType(entries);

        assertEquals(3L, counts.get("ALL"));
        assertEquals(2L, counts.get("SEARCH"));
        assertEquals(1L, counts.get("UNKNOWN"));
        // 各分类之和必须等于总数，否则界面上的类型统计会与总数对不上。
        assertEquals(counts.get("ALL"),
                counts.get("SEARCH") + counts.get("UNKNOWN"));
    }

    private CaseDtos.HistoryEntry historyEntry(Long id, String actionType) {
        return new CaseDtos.HistoryEntry(id, actionType, null, "文本 " + id, LocalDateTime.now());
    }

    private String unlockReason(String condition,
                                Set<String> foundClueCodes,
                                Set<Long> solvedPuzzleIds,
                                Set<String> investigatedKeys) {
        return CaseQueryService.lockedReason(
                condition, foundClueCodes, solvedPuzzleIds, investigatedKeys, LOCATION_NAMES);
    }
}
