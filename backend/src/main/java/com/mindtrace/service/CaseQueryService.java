package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.dto.CaseDtos;
import com.mindtrace.entity.CaseFile;
import com.mindtrace.entity.CaseLocation;
import com.mindtrace.entity.CaseSource;
import com.mindtrace.entity.CaseTimeline;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.GameRecord;
import com.mindtrace.entity.InvestigationRecord;
import com.mindtrace.entity.Npc;
import com.mindtrace.entity.Puzzle;
import com.mindtrace.entity.Suspect;
import com.mindtrace.entity.UserClue;
import com.mindtrace.entity.UserPuzzle;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.CaseFileMapper;
import com.mindtrace.mapper.CaseLocationMapper;
import com.mindtrace.mapper.CaseSourceMapper;
import com.mindtrace.mapper.CaseTimelineMapper;
import com.mindtrace.mapper.ClueMapper;
import com.mindtrace.mapper.GameRecordMapper;
import com.mindtrace.mapper.InvestigationRecordMapper;
import com.mindtrace.mapper.NpcMapper;
import com.mindtrace.mapper.PuzzleMapper;
import com.mindtrace.mapper.SuspectMapper;
import com.mindtrace.mapper.UserClueMapper;
import com.mindtrace.mapper.UserPuzzleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CaseQueryService {
    private final CaseFileMapper caseFileMapper;
    private final CaseTimelineMapper timelineMapper;
    private final CaseLocationMapper locationMapper;
    private final NpcMapper npcMapper;
    private final SuspectMapper suspectMapper;
    private final CaseSourceMapper sourceMapper;
    private final ClueMapper clueMapper;
    private final PuzzleMapper puzzleMapper;
    private final UserClueMapper userClueMapper;
    private final UserPuzzleMapper userPuzzleMapper;
    private final InvestigationRecordMapper investigationRecordMapper;
    private final GameRecordMapper gameRecordMapper;

    /**
     * 案件列表。带上当前玩家的存档进度，前端据此显示「开始调查 / 继续调查 / 已完成」。
     * <p>
     * 进度是一次性批量算出来的（固定几次查询），不会随案件数量增长成 N+1。
     */
    public List<CaseDtos.CaseSummary> listCases(Long userId) {
        List<CaseFile> cases = caseFileMapper.selectList(Wrappers.<CaseFile>lambdaQuery()
                .orderByAsc(CaseFile::getId));
        if (cases.isEmpty()) {
            return List.of();
        }
        Map<Long, CaseDtos.PlayerProgress> progressByCase = userId == null
                ? Map.of()
                : playerProgressByCase(cases, userId);
        return cases.stream()
                .map(item -> toSummary(item, progressByCase.get(item.getId())))
                .toList();
    }

    /**
     * 批量计算玩家在每个案件的进度。
     * <p>
     * 固定 7 次查询（线索/地点/谜题总量、已发现线索、调查记录、已解谜题、已完成记录），
     * 与案件数量无关。
     */
    private Map<Long, CaseDtos.PlayerProgress> playerProgressByCase(List<CaseFile> cases, Long userId) {
        List<Long> caseIds = cases.stream().map(CaseFile::getId).toList();

        List<Clue> allClues = clueMapper.selectList(Wrappers.<Clue>lambdaQuery()
                .in(Clue::getCaseId, caseIds));
        Map<Long, Long> totalClues = countByCase(allClues, Clue::getCaseId);

        List<CaseLocation> allLocations = locationMapper.selectList(Wrappers.<CaseLocation>lambdaQuery()
                .in(CaseLocation::getCaseId, caseIds));
        Map<Long, Long> totalLocations = countByCase(allLocations, CaseLocation::getCaseId);
        Map<Long, Long> locationCaseId = allLocations.stream()
                .collect(Collectors.toMap(CaseLocation::getId, CaseLocation::getCaseId, (left, right) -> left));

        List<Puzzle> allPuzzles = puzzleMapper.selectList(Wrappers.<Puzzle>lambdaQuery()
                .in(Puzzle::getCaseId, caseIds));
        Map<Long, Long> totalPuzzles = countByCase(allPuzzles, Puzzle::getCaseId);
        Map<Long, Long> puzzleCaseId = allPuzzles.stream()
                .collect(Collectors.toMap(Puzzle::getId, Puzzle::getCaseId, (left, right) -> left));

        Map<Long, Long> discoveredClues = countByCase(
                userClueMapper.selectList(Wrappers.<UserClue>lambdaQuery()
                        .eq(UserClue::getUserId, userId)
                        .in(UserClue::getCaseId, caseIds)),
                UserClue::getCaseId);

        // 同一地点调查多次只算一次
        Map<Long, Set<Long>> investigatedByCase = new HashMap<>();
        for (InvestigationRecord record : investigationRecordMapper.selectList(
                Wrappers.<InvestigationRecord>lambdaQuery()
                        .eq(InvestigationRecord::getUserId, userId)
                        .in(InvestigationRecord::getCaseId, caseIds))) {
            Long caseId = locationCaseId.get(record.getLocationId());
            if (caseId != null) {
                investigatedByCase.computeIfAbsent(caseId, key -> new HashSet<>()).add(record.getLocationId());
            }
        }

        Map<Long, Long> solvedPuzzles = new HashMap<>();
        List<Long> puzzleIds = allPuzzles.stream().map(Puzzle::getId).toList();
        if (!puzzleIds.isEmpty()) {
            for (UserPuzzle userPuzzle : userPuzzleMapper.selectList(Wrappers.<UserPuzzle>lambdaQuery()
                    .eq(UserPuzzle::getUserId, userId)
                    .in(UserPuzzle::getPuzzleId, puzzleIds)
                    .eq(UserPuzzle::getCompleted, true))) {
                Long caseId = puzzleCaseId.get(userPuzzle.getPuzzleId());
                if (caseId != null) {
                    solvedPuzzles.merge(caseId, 1L, Long::sum);
                }
            }
        }

        Set<Long> completedCases = gameRecordMapper.selectList(Wrappers.<GameRecord>lambdaQuery()
                        .eq(GameRecord::getUserId, userId)
                        .in(GameRecord::getCaseId, caseIds)
                        .eq(GameRecord::getStatus, "COMPLETED"))
                .stream()
                .map(GameRecord::getCaseId)
                .collect(Collectors.toSet());

        Map<Long, CaseDtos.PlayerProgress> result = new HashMap<>();
        for (CaseFile item : cases) {
            long caseId = item.getId();
            long total = totalClues.getOrDefault(caseId, 0L);
            long found = discoveredClues.getOrDefault(caseId, 0L);
            long totalLocationCount = totalLocations.getOrDefault(caseId, 0L);
            long visited = investigatedByCase.getOrDefault(caseId, Set.of()).size();
            long totalPuzzleCount = totalPuzzles.getOrDefault(caseId, 0L);
            long solved = solvedPuzzles.getOrDefault(caseId, 0L);
            // 「开始了」的定义：有调查记录、有已发现线索、或解过谜题。
            boolean started = visited > 0 || found > 0 || solved > 0;
            result.put(caseId, new CaseDtos.PlayerProgress(
                    started,
                    completedCases.contains(caseId),
                    overallPercent(percent(visited, totalLocationCount), percent(found, total),
                            puzzlePercent(solved, totalPuzzleCount)),
                    (int) found, (int) total,
                    (int) visited, (int) totalLocationCount,
                    (int) solved, (int) totalPuzzleCount));
        }
        return result;
    }

    private static <T> Map<Long, Long> countByCase(List<T> rows,
                                                   java.util.function.Function<T, Long> caseIdGetter) {
        Map<Long, Long> counts = new HashMap<>();
        for (T row : rows) {
            Long caseId = caseIdGetter.apply(row);
            if (caseId != null) {
                counts.merge(caseId, 1L, Long::sum);
            }
        }
        return counts;
    }

    public CaseFile requireCase(Long caseId) {
        CaseFile caseFile = caseFileMapper.selectById(caseId);
        if (caseFile == null) {
            throw new BusinessException(404, "案件不存在");
        }
        return caseFile;
    }

    public CaseDtos.CaseDetail detail(Long caseId, Long userId) {
        CaseFile caseFile = requireCase(caseId);
        List<CaseTimeline> timeline = timelineMapper.selectList(Wrappers.<CaseTimeline>lambdaQuery()
                .eq(CaseTimeline::getCaseId, caseId)
                .orderByAsc(CaseTimeline::getSortOrder));
        List<CaseDtos.LocationView> locations = locationViews(caseId, userId);
        List<Npc> npcs = npcMapper.selectList(Wrappers.<Npc>lambdaQuery()
                .eq(Npc::getCaseId, caseId)
                .orderByAsc(Npc::getId));
        List<Suspect> suspects = suspectMapper.selectList(Wrappers.<Suspect>lambdaQuery()
                .eq(Suspect::getCaseId, caseId)
                .orderByAsc(Suspect::getId));
        List<CaseSource> sources = sourceMapper.selectList(Wrappers.<CaseSource>lambdaQuery()
                .eq(CaseSource::getCaseId, caseId)
                .orderByDesc(CaseSource::getPublishedAt));
        List<Puzzle> puzzles = puzzleMapper.selectList(Wrappers.<Puzzle>lambdaQuery()
                .eq(Puzzle::getCaseId, caseId)
                .orderByAsc(Puzzle::getId));
        List<Clue> discovered = discoveredClues(caseId, userId);
        CaseDtos.Progress progress = progress(caseId, userId);
        return new CaseDtos.CaseDetail(
                caseFile, timeline, locations, npcs, suspects, sources, discovered, puzzles, progress);
    }

    /**
     * 计算调查地图上每个节点的状态。
     * <p>
     * 状态完全由后端根据玩家在该案的真实记录推导，前端只做渲染，不得自行推断或改写。
     * 前置条件写在 {@code case_locations.unlock_condition}，支持逗号分隔的「全部满足」语义：
     * <ul>
     *     <li>{@code public} —— 默认开放</li>
     *     <li>{@code clue:CLUE-002} —— 需先发现该线索</li>
     *     <li>{@code puzzle:1} —— 需先破解该谜题（按谜题 ID）</li>
     *     <li>{@code location:lobby} —— 需先调查该地点</li>
     * </ul>
     */
    public List<CaseDtos.LocationView> locationViews(Long caseId, Long userId) {
        List<CaseLocation> locations = locationMapper.selectList(Wrappers.<CaseLocation>lambdaQuery()
                .eq(CaseLocation::getCaseId, caseId)
                .orderByAsc(CaseLocation::getId));
        if (locations.isEmpty()) {
            return List.of();
        }
        Map<String, String> locationNames = locations.stream()
                .collect(Collectors.toMap(
                        CaseLocation::getLocationKey, CaseLocation::getName, (left, right) -> left));

        // 隐藏线索属于额外奖励，不参与「地点是否已完成」的判定，否则玩家永远看不到 COMPLETED。
        Map<String, List<Clue>> cluesByLocation = clueMapper.selectList(Wrappers.<Clue>lambdaQuery()
                        .eq(Clue::getCaseId, caseId)
                        .isNotNull(Clue::getLocationKey))
                .stream()
                .filter(clue -> !Boolean.TRUE.equals(clue.getIsHidden()))
                .collect(Collectors.groupingBy(Clue::getLocationKey));

        Set<String> foundClueCodes = Set.of();
        Set<Long> solvedPuzzleIds = Set.of();
        Set<String> investigatedKeys = Set.of();
        if (userId != null) {
            List<Long> clueIds = userClueMapper.selectList(Wrappers.<UserClue>lambdaQuery()
                            .eq(UserClue::getUserId, userId)
                            .eq(UserClue::getCaseId, caseId))
                    .stream()
                    .map(UserClue::getClueId)
                    .toList();
            if (!clueIds.isEmpty()) {
                foundClueCodes = clueMapper.selectBatchIds(clueIds).stream()
                        .map(Clue::getClueCode)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
            }

            List<Long> puzzleIds = puzzleMapper.selectList(Wrappers.<Puzzle>lambdaQuery()
                            .eq(Puzzle::getCaseId, caseId))
                    .stream()
                    .map(Puzzle::getId)
                    .toList();
            if (!puzzleIds.isEmpty()) {
                solvedPuzzleIds = userPuzzleMapper.selectList(Wrappers.<UserPuzzle>lambdaQuery()
                                .eq(UserPuzzle::getUserId, userId)
                                .in(UserPuzzle::getPuzzleId, puzzleIds)
                                .eq(UserPuzzle::getCompleted, true))
                        .stream()
                        .map(UserPuzzle::getPuzzleId)
                        .collect(Collectors.toSet());
            }

            Set<Long> investigatedIds = investigationRecordMapper.selectList(
                            Wrappers.<InvestigationRecord>lambdaQuery()
                                    .eq(InvestigationRecord::getUserId, userId)
                                    .eq(InvestigationRecord::getCaseId, caseId))
                    .stream()
                    .map(InvestigationRecord::getLocationId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            investigatedKeys = locations.stream()
                    .filter(item -> investigatedIds.contains(item.getId()))
                    .map(CaseLocation::getLocationKey)
                    .collect(Collectors.toSet());
        }

        // 供 lambda 捕获的不可变副本（上面三个变量在 if 块内被重新赋值，本身不是 effectively final）。
        Set<String> finalClueCodes = foundClueCodes;
        Set<Long> finalPuzzleIds = solvedPuzzleIds;
        Set<String> finalInvestigatedKeys = investigatedKeys;

        List<CaseDtos.LocationView> views = new ArrayList<>(locations.size());
        for (CaseLocation location : locations) {
            List<Clue> localClues = cluesByLocation.getOrDefault(location.getLocationKey(), List.of());
            int found = (int) localClues.stream()
                    .filter(clue -> clue.getClueCode() != null && finalClueCodes.contains(clue.getClueCode()))
                    .count();
            String lockedReason = lockedReason(location.getUnlockCondition(),
                    finalClueCodes, finalPuzzleIds, finalInvestigatedKeys, locationNames);
            views.add(new CaseDtos.LocationView(
                    location.getId(),
                    location.getLocationKey(),
                    location.getName(),
                    location.getDescription(),
                    location.getIcon(),
                    location.getMapX(),
                    location.getMapY(),
                    location.getUnlockCondition(),
                    nodeStatus(lockedReason, investigatedKeys.contains(location.getLocationKey()),
                            found, localClues.size()),
                    localClues.size(),
                    found,
                    lockedReason));
        }
        return views;
    }

    /**
     * 前置条件未满足时返回中文说明，已满足时返回 {@code null}。
     * 未知的前置类型保守处理为「未开放」，避免把拼错的条件误判成开放节点。
     * <p>
     * 纯函数，不依赖任何 mapper，便于直接单测。
     */
    static String lockedReason(String unlockCondition,
                               Set<String> foundClueCodes,
                               Set<Long> solvedPuzzleIds,
                               Set<String> investigatedKeys,
                               Map<String, String> locationNames) {
        if (!StringUtils.hasText(unlockCondition)) {
            return null;
        }
        String raw = unlockCondition.strip();
        if ("public".equalsIgnoreCase(raw) || "*".equals(raw)) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        for (String token : raw.split(",")) {
            String item = token.strip();
            if (item.isEmpty()) {
                continue;
            }
            int separator = item.indexOf(':');
            String kind = (separator < 0 ? item : item.substring(0, separator)).toLowerCase(Locale.ROOT);
            String value = separator < 0 ? "" : item.substring(separator + 1).strip();
            switch (kind) {
                case "clue" -> {
                    if (!foundClueCodes.contains(value)) {
                        reasons.add("需要先发现线索 " + value);
                    }
                }
                case "puzzle" -> {
                    Long puzzleId = parseLong(value);
                    if (puzzleId == null || !solvedPuzzleIds.contains(puzzleId)) {
                        reasons.add("需要先破解谜题 #" + value);
                    }
                }
                case "location" -> {
                    if (!investigatedKeys.contains(value)) {
                        reasons.add("需要先调查「" + locationNames.getOrDefault(value, value) + "」");
                    }
                }
                default -> reasons.add("该地点尚未开放");
            }
        }
        return reasons.isEmpty() ? null : String.join("，", reasons);
    }

    static String nodeStatus(String lockedReason, boolean investigated, int found, int total) {
        if (lockedReason != null) {
            return "LOCKED";
        }
        if (!investigated) {
            return "AVAILABLE";
        }
        return found >= total ? "COMPLETED" : "INVESTIGATED";
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public List<Clue> discoveredClues(Long caseId, Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<Long> clueIds = userClueMapper.selectList(Wrappers.<UserClue>lambdaQuery()
                        .eq(UserClue::getUserId, userId)
                        .eq(UserClue::getCaseId, caseId)
                        .orderByDesc(UserClue::getDiscoveredAt))
                .stream()
                .map(UserClue::getClueId)
                .toList();
        if (clueIds.isEmpty()) {
            return List.of();
        }
        return clueMapper.selectBatchIds(clueIds);
    }

    /**
     * 服务端扫描上限。
     * <p>
     * 关键词必须在**展示文本**上过滤，而不是数据库里的原文 ——
     * REASONING 记录的原文带着审计用的原始 AI JSON，按原文搜会搜出
     * 「看得见的结果里根本没有这个词」的记录。代价是关键词过滤只能放在内存里做，
     * 所以这里给扫描量设个上限，并在响应里如实告诉前端「结果可能被截断」。
     */
    private static final int HISTORY_SCAN_LIMIT = 2000;
    private static final int HISTORY_DEFAULT_SIZE = 20;
    private static final int HISTORY_MAX_SIZE = 100;

    /**
     * 玩家在本案的调查日志（地点调查、推理分析、谜题解锁）。
     * 支持按类型、关键词、日期范围筛选，并分页返回。
     */
    public CaseDtos.HistoryPage history(Long caseId, Long userId, CaseDtos.HistoryQuery query) {
        requireCase(caseId);
        int page = normalizePage(query.page());
        int size = normalizeSize(query.size());
        // 「ALL」必须和「不传」归一到同一个值再往下走：SQL 下推那步只判断「有没有文本」，
        // 若把字面量 ALL 直接拼进 WHERE，就会变成 action_type = 'ALL' 而返回空结果 ——
        // 前端恰好传的是 undefined 才没暴露，接口自己不能依赖调用方的自觉。
        String type = normalizeType(query.type());
        if (userId == null) {
            return emptyPage(page, size);
        }

        Map<Long, String> locationNames = locationMapper.selectList(Wrappers.<CaseLocation>lambdaQuery()
                        .eq(CaseLocation::getCaseId, caseId))
                .stream()
                .collect(Collectors.toMap(
                        CaseLocation::getId, CaseLocation::getName, (left, right) -> left));

        // 日期范围与类型能直接下推到 SQL（可走索引），关键词不行 —— 见 HISTORY_SCAN_LIMIT。
        List<InvestigationRecord> records = investigationRecordMapper.selectList(
                Wrappers.<InvestigationRecord>lambdaQuery()
                        .eq(InvestigationRecord::getUserId, userId)
                        .eq(InvestigationRecord::getCaseId, caseId)
                        .eq(type != null, InvestigationRecord::getActionType, type)
                        .ge(query.from() != null, InvestigationRecord::getCreatedAt, rangeStart(query.from()))
                        .le(query.to() != null, InvestigationRecord::getCreatedAt, rangeEnd(query.to()))
                        .orderByDesc(InvestigationRecord::getCreatedAt)
                        .last("LIMIT " + HISTORY_SCAN_LIMIT));

        // 恰好取满上限时无法区分「正好这么多」和「被截断」，按可能截断处理：
        // 多提示一句不完整，好过让玩家以为这就是全部。
        boolean truncated = records.size() >= HISTORY_SCAN_LIMIT;

        // 关键词在展示文本上过滤：搜到的就是看得见的。
        List<CaseDtos.HistoryEntry> matched = records.stream()
                .map(record -> new CaseDtos.HistoryEntry(
                        record.getId(),
                        record.getActionType(),
                        record.getLocationId() == null ? null : locationNames.get(record.getLocationId()),
                        displayText(record.getActionType(), record.getResultText()),
                        record.getCreatedAt()))
                .filter(entry -> matchesKeyword(entry.resultText(), query.keyword()))
                .toList();

        // 类型计数在「日期 + 关键词」之后、**类型筛选之前**统计，
        // 否则点进某个类型之后其他标签的计数会全部变成 0。
        Map<String, Long> typeCounts = countByType(matched);
        List<CaseDtos.HistoryEntry> typed = filterByType(matched, type);
        return buildPage(typed, typeCounts, page, size, truncated);
    }

    /** 「ALL」与空白都表示不按类型筛选；归一到 null，SQL 下推与内存过滤才有同一套语义。 */
    static String normalizeType(String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type.strip())) {
            return null;
        }
        return type.strip();
    }

    /** from 是日期，取当天 00:00:00。 */
    static LocalDateTime rangeStart(LocalDate from) {
        return from == null ? null : from.atStartOfDay();
    }

    /**
     * to 是日期，必须包含当天整天。
     * 直接 atStartOfDay() 会让「筛选到今天」漏掉今天的所有记录 —— 这类 off-by-one
     * 在日期筛选里极其常见，所以这里固定取当天最后一刻，并由测试钉住。
     */
    static LocalDateTime rangeEnd(LocalDate to) {
        return to == null ? null : to.atTime(LocalTime.MAX);
    }

    /** 关键词匹配展示文本，大小写不敏感；空关键词视为全部命中。 */
    static boolean matchesKeyword(String displayText, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        if (displayText == null) {
            return false;
        }
        return displayText.toLowerCase(Locale.ROOT).contains(keyword.strip().toLowerCase(Locale.ROOT));
    }

    static List<CaseDtos.HistoryEntry> filterByType(List<CaseDtos.HistoryEntry> entries, String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) {
            return entries;
        }
        return entries.stream()
                .filter(entry -> type.equalsIgnoreCase(entry.actionType()))
                .toList();
    }

    static Map<String, Long> countByType(List<CaseDtos.HistoryEntry> entries) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", (long) entries.size());
        for (CaseDtos.HistoryEntry entry : entries) {
            String type = entry.actionType() == null ? "UNKNOWN" : entry.actionType();
            counts.merge(type, 1L, Long::sum);
        }
        return counts;
    }

    static int normalizePage(int page) {
        return page < 1 ? 1 : page;
    }

    static int normalizeSize(int size) {
        if (size < 1) {
            return HISTORY_DEFAULT_SIZE;
        }
        return Math.min(size, HISTORY_MAX_SIZE);
    }

    static int totalPages(long total, int size) {
        if (size < 1) {
            return 0;
        }
        return (int) ((total + size - 1) / size);
    }

    private static CaseDtos.HistoryPage emptyPage(int page, int size) {
        return new CaseDtos.HistoryPage(List.of(), page, size, 0, 0, false, false, Map.of("ALL", 0L));
    }

    /** package-private 是为了能被单测直接覆盖：分页切片是最容易出 off-by-one 的地方。 */
    static CaseDtos.HistoryPage buildPage(
            List<CaseDtos.HistoryEntry> entries,
            Map<String, Long> typeCounts,
            int page,
            int size,
            boolean truncated) {
        long total = entries.size();
        int pages = totalPages(total, size);
        int fromIndex = Math.min((page - 1) * size, entries.size());
        int toIndex = Math.min(fromIndex + size, entries.size());
        return new CaseDtos.HistoryPage(
                entries.subList(fromIndex, toIndex),
                page,
                size,
                total,
                pages,
                page < pages,
                truncated,
                typeCounts);
    }

    /**
     * 把 investigation_records.result_text 转成适合玩家阅读的文本。
     * <p>
     * REASONING 记录写入时是「玩家假设 + 原始 AI JSON」，原始 JSON 保留在数据库里用于审计，
     * 但不应出现在玩家界面上，因此这里只截取假设部分。
     */
    static String displayText(String actionType, String resultText) {
        if (resultText == null || !"REASONING".equals(actionType)) {
            return resultText;
        }
        int marker = resultText.indexOf("\n\n{");
        return marker > 0 ? resultText.substring(0, marker).strip() : resultText.strip();
    }

    public CaseDtos.Progress progress(Long caseId, Long userId) {
        long totalLocations = locationMapper.selectCount(Wrappers.<CaseLocation>lambdaQuery()
                .eq(CaseLocation::getCaseId, caseId));
        long totalClues = clueMapper.selectCount(Wrappers.<Clue>lambdaQuery()
                .eq(Clue::getCaseId, caseId));
        long totalPuzzles = puzzleMapper.selectCount(Wrappers.<Puzzle>lambdaQuery()
                .eq(Puzzle::getCaseId, caseId));

        long discoveredClues = 0;
        long solvedPuzzles = 0;
        long investigatedLocations = 0;
        boolean completed = false;
        if (userId != null) {
            discoveredClues = userClueMapper.selectCount(Wrappers.<UserClue>lambdaQuery()
                    .eq(UserClue::getUserId, userId)
                    .eq(UserClue::getCaseId, caseId));
            List<Long> puzzleIds = puzzleMapper.selectList(Wrappers.<Puzzle>lambdaQuery()
                            .eq(Puzzle::getCaseId, caseId))
                    .stream().map(Puzzle::getId).toList();
            if (!puzzleIds.isEmpty()) {
                solvedPuzzles = userPuzzleMapper.selectCount(Wrappers.<UserPuzzle>lambdaQuery()
                        .eq(UserPuzzle::getUserId, userId)
                        .in(UserPuzzle::getPuzzleId, puzzleIds)
                        .eq(UserPuzzle::getCompleted, true));
            }
            Set<Long> locationIds = locationMapper.selectList(Wrappers.<CaseLocation>lambdaQuery()
                            .eq(CaseLocation::getCaseId, caseId))
                    .stream().map(CaseLocation::getId).collect(java.util.stream.Collectors.toSet());
            if (!locationIds.isEmpty()) {
                investigatedLocations = investigationRecordMapper.selectList(Wrappers
                                .<InvestigationRecord>lambdaQuery()
                                .eq(InvestigationRecord::getUserId, userId)
                                .eq(InvestigationRecord::getCaseId, caseId))
                        .stream()
                        .map(InvestigationRecord::getLocationId)
                        .filter(locationIds::contains)
                        .distinct()
                        .count();
            }
            completed = isCompleted(caseId, userId);
        }

        int investigationPercent = percent(investigatedLocations, totalLocations);
        int cluePercent = percent(discoveredClues, totalClues);
        int puzzlePercent = puzzlePercent(solvedPuzzles, totalPuzzles);
        return new CaseDtos.Progress(
                (int) investigatedLocations,
                (int) totalLocations,
                (int) discoveredClues,
                (int) totalClues,
                (int) solvedPuzzles,
                (int) totalPuzzles,
                investigationPercent,
                cluePercent,
                overallPercent(investigationPercent, cluePercent, puzzlePercent),
                completed);
    }

    private boolean isCompleted(Long caseId, Long userId) {
        return gameRecordMapper.selectCount(Wrappers.<GameRecord>lambdaQuery()
                .eq(GameRecord::getUserId, userId)
                .eq(GameRecord::getCaseId, caseId)
                .eq(GameRecord::getStatus, "COMPLETED")) > 0;
    }

    private int percent(long value, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round(value * 100.0 / total));
    }

    /**
     * 谜题维度的百分比。本案没有谜题时给满分，
     * 否则「没有谜题的案件」会因为这一项恒为 0 而永远显示未完成。
     */
    static int puzzlePercent(long solved, long total) {
        return total <= 0 ? 100 : (int) Math.min(100, Math.round(solved * 100.0 / total));
    }

    /**
     * 综合完成度：调查 30% + 线索 30% + 谜题 40%。
     * <p>
     * 由 Java 计算，前端不再自己加权——否则列表页和详情页会算出两个不一样的数字。
     */
    static int overallPercent(int investigationPercent, int cluePercent, int puzzlePercent) {
        double weighted = investigationPercent * 0.3 + cluePercent * 0.3 + puzzlePercent * 0.4;
        return (int) Math.min(100, Math.round(weighted));
    }

    private CaseDtos.CaseSummary toSummary(CaseFile item, CaseDtos.PlayerProgress playerProgress) {
        return new CaseDtos.CaseSummary(
                item.getId(), item.getCaseCode(), item.getTitle(), item.getSubtitle(), item.getRealName(),
                item.getSummary(), item.getCaseType(), item.getDifficulty(), item.getEra(), item.getLocation(),
                item.getCoverUrl(), item.getStatus(), item.getCompletion(), item.getPlayers(),
                item.getContentRating(), playerProgress);
    }
}
