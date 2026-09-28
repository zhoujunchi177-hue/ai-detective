package com.mindtrace.dto;

import com.mindtrace.entity.CaseFile;
import com.mindtrace.entity.CaseSource;
import com.mindtrace.entity.CaseTimeline;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.Npc;
import com.mindtrace.entity.Puzzle;
import com.mindtrace.entity.Suspect;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class CaseDtos {

    private CaseDtos() {
    }

    public record CaseSummary(
            Long id,
            String caseCode,
            String title,
            String subtitle,
            String realName,
            String summary,
            String caseType,
            String difficulty,
            String era,
            String location,
            String coverUrl,
            String status,
            Integer completion,
            Integer players,
            Integer contentRating,
            PlayerProgress playerProgress) {
    }

    /**
     * 玩家在本案的存档进度。前端据此显示「开始调查 / 继续调查 / 已完成」。
     * <p>
     * 未开始的案件**仍然返回这个对象**，只是 {@code started = false}、各项计数为 0。
     * 这样界面在开局前也能显示「0/14 线索」，而「没开始」与「开始了但一条线索都没拿到」
     * 由 {@code started} 明确区分，不需要靠 null 判断。
     * 只有未登录（匿名）请求时这个字段才整体缺席。
     * <p>
     * 注意区分：{@code CaseSummary.completion} 是案件**资料完整度**（静态内容量），
     * 这里的 {@code percent} 才是**玩家自己的调查进度**。两者含义不同，界面必须分开标注。
     */
    public record PlayerProgress(
            boolean started,
            boolean completed,
            int percent,
            int discoveredClues,
            int totalClues,
            int investigatedLocations,
            int totalLocations,
            int solvedPuzzles,
            int totalPuzzles) {
    }

    public record CaseDetail(
            CaseFile caseInfo,
            List<CaseTimeline> timeline,
            List<LocationView> locations,
            List<Npc> npcs,
            List<Suspect> suspects,
            List<CaseSource> sources,
            List<Clue> discoveredClues,
            List<Puzzle> puzzles,
            Progress progress) {
    }

    /**
     * 调查地图节点视图。
     * <p>
     * 节点状态由后端计算，前端只负责渲染，不得自行推断。
     * <ul>
     *     <li>{@code LOCKED} —— 前置条件未满足，不可调查</li>
     *     <li>{@code AVAILABLE} —— 已解锁但尚未调查</li>
     *     <li>{@code INVESTIGATED} —— 已调查，但本地点仍有未发现的常规线索</li>
     *     <li>{@code COMPLETED} —— 已调查且本地点常规线索已全部发现</li>
     * </ul>
     * {@code clueCount} 只统计非隐藏线索（隐藏线索属于额外奖励，不参与完成度判定），
     * {@code foundClueCount} 是其中已被玩家发现的条数。{@code lockedReason} 仅在 LOCKED 时有值。
     */
    public record LocationView(
            Long id,
            String locationKey,
            String name,
            String description,
            String icon,
            Integer mapX,
            Integer mapY,
            String unlockCondition,
            String status,
            int clueCount,
            int foundClueCount,
            String lockedReason) {
    }

    /**
     * 案件进度。百分比全部由 Java 计算，前端只渲染，不自己加权。
     * {@code overallPercent} 是界面顶部那根进度条用的综合完成度。
     */
    public record Progress(
            int investigatedLocations,
            int totalLocations,
            int discoveredClues,
            int totalDiscoverableClues,
            int solvedPuzzles,
            int totalPuzzles,
            int investigationPercent,
            int cluePercent,
            int overallPercent,
            boolean completed) {
    }

    public record InvestigateRequest(String locationKey, String action) {
    }

    public record InvestigateResult(
            LocationView location,
            String narrative,
            List<Clue> unlockedClues,
            Progress progress) {
    }

    /**
     * 谜题答案。长度上限与 {@code user_puzzles.answer} 列（VARCHAR(1000)）对齐 ——
     * 否则超长答案会在写库时抛数据层异常，被兜底 handler 变成 500。
     */
    public record PuzzleAnswerRequest(
            @Size(max = 1000, message = "答案最多 1000 字") String answer) {
    }

    public record PuzzleResult(
            boolean correct,
            String message,
            List<Clue> unlockedClues,
            Progress progress) {
    }

    /**
     * 玩家在本案的调查日志条目。
     * actionType：SEARCH（调查地点）/ REASONING（推理分析）/ PUZZLE 等。
     * locationName 可能为空（例如推理记录不属于任何地点）。
     */
    public record HistoryEntry(
            Long id,
            String actionType,
            String locationName,
            String resultText,
            LocalDateTime createdAt) {
    }

    /**
     * 日志查询条件。
     * <p>
     * from / to 是**日期**而不是时间点，且 to 含当天整天 ——
     * 让调用方自己算「当天最后一毫秒」是这类筛选最常见的 off-by-one 来源。
     */
    public record HistoryQuery(
            String type,
            String keyword,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
    }

    /**
     * 日志分页结果。
     *
     * @param entries     当前页的条目
     * @param total       命中**全部筛选条件**（含类型）的条数
     * @param typeCounts  各类型在「日期 + 关键词」筛选下（**不含类型**）的条数，
     *                    供类型标签显示计数 —— 计数必须在点进某个类型之前就能看到
     * @param truncated   命中集是否触到了服务端扫描上限。为 true 时 total 是下界而非精确值，
     *                    界面必须如实说明，不能让玩家以为「就这么多」。
     */
    public record HistoryPage(
            List<HistoryEntry> entries,
            int page,
            int size,
            long total,
            int totalPages,
            boolean hasMore,
            boolean truncated,
            Map<String, Long> typeCounts) {
    }

    public record NpcDetail(Npc npc, List<ChatView> history) {
    }

    public record ChatView(
            Long id,
            String role,
            String content,
            LocalDateTime createdAt) {
    }
}

