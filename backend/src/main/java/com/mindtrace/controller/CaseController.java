package com.mindtrace.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.common.ApiResponse;
import com.mindtrace.dto.CaseDtos;
import com.mindtrace.dto.EvidenceDtos;
import com.mindtrace.entity.CaseSource;
import com.mindtrace.entity.CaseTimeline;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.Npc;
import com.mindtrace.entity.Puzzle;
import com.mindtrace.entity.Suspect;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.CaseSourceMapper;
import com.mindtrace.mapper.CaseTimelineMapper;
import com.mindtrace.mapper.NpcMapper;
import com.mindtrace.mapper.PuzzleMapper;
import com.mindtrace.mapper.SuspectMapper;
import com.mindtrace.security.UserContext;
import com.mindtrace.service.CaseQueryService;
import com.mindtrace.service.EvidenceBoardService;
import com.mindtrace.service.InvestigationService;
import com.mindtrace.service.PuzzleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class CaseController {
    private final CaseQueryService caseQueryService;
    private final InvestigationService investigationService;
    private final PuzzleService puzzleService;
    private final EvidenceBoardService evidenceBoardService;
    private final CaseTimelineMapper timelineMapper;
    private final SuspectMapper suspectMapper;
    private final CaseSourceMapper sourceMapper;
    private final NpcMapper npcMapper;
    private final PuzzleMapper puzzleMapper;

    @GetMapping
    public ApiResponse<List<CaseDtos.CaseSummary>> list() {
        return ApiResponse.ok(caseQueryService.listCases(UserContext.currentUserIdOrNull()));
    }

    @GetMapping("/{id}")
    public ApiResponse<CaseDtos.CaseDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(caseQueryService.detail(id, UserContext.currentUserIdOrNull()));
    }

    @GetMapping("/{id}/suspects")
    public ApiResponse<List<Suspect>> suspects(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        return ApiResponse.ok(suspectMapper.selectList(Wrappers.<Suspect>lambdaQuery()
                .eq(Suspect::getCaseId, id)
                .orderByAsc(Suspect::getId)));
    }

    @GetMapping("/{id}/clues")
    public ApiResponse<List<Clue>> clues(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        Long userId = UserContext.currentUserIdOrNull();
        return ApiResponse.ok(userId == null ? List.of() : caseQueryService.discoveredClues(id, userId));
    }

    @GetMapping("/{id}/timeline")
    public ApiResponse<List<CaseTimeline>> timeline(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        return ApiResponse.ok(timelineMapper.selectList(Wrappers.<CaseTimeline>lambdaQuery()
                .eq(CaseTimeline::getCaseId, id)
                .orderByAsc(CaseTimeline::getSortOrder)));
    }

    @GetMapping("/{id}/progress")
    public ApiResponse<CaseDtos.Progress> progress(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        return ApiResponse.ok(caseQueryService.progress(id, UserContext.currentUserIdOrNull()));
    }

    /**
     * 玩家在本案的调查日志（时间倒序，支持类型 / 关键词 / 日期范围筛选并分页）。
     * <p>
     * from / to 是日期（yyyy-MM-dd），to 含当天整天。
     * type 传 ALL 或留空表示不按类型筛选。
     */
    @GetMapping("/{id}/history")
    public ApiResponse<CaseDtos.HistoryPage> history(
            @PathVariable Long id,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(caseQueryService.history(
                id,
                UserContext.currentUserIdOrNull(),
                new CaseDtos.HistoryQuery(type, keyword, from, to, page, size)));
    }

    @GetMapping("/{id}/sources")
    public ApiResponse<List<CaseSource>> sources(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        return ApiResponse.ok(sourceMapper.selectList(Wrappers.<CaseSource>lambdaQuery()
                .eq(CaseSource::getCaseId, id)
                .orderByDesc(CaseSource::getPublishedAt)));
    }

    /**
     * 调查地图节点列表。状态（LOCKED / AVAILABLE / INVESTIGATED / COMPLETED）由后端计算。
     */
    @GetMapping("/{id}/locations")
    public ApiResponse<List<CaseDtos.LocationView>> locations(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        return ApiResponse.ok(caseQueryService.locationViews(id, UserContext.currentUserIdOrNull()));
    }

    @GetMapping("/{id}/npcs")
    public ApiResponse<List<Npc>> npcs(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        return ApiResponse.ok(npcMapper.selectList(Wrappers.<Npc>lambdaQuery()
                .eq(Npc::getCaseId, id)
                .orderByAsc(Npc::getId)));
    }

    @GetMapping("/{id}/puzzles")
    public ApiResponse<List<Puzzle>> puzzles(@PathVariable Long id) {
        caseQueryService.requireCase(id);
        return ApiResponse.ok(puzzleMapper.selectList(Wrappers.<Puzzle>lambdaQuery()
                .eq(Puzzle::getCaseId, id)
                .orderByAsc(Puzzle::getId)));
    }

    @PostMapping("/{id}/investigate")
    public ApiResponse<CaseDtos.InvestigateResult> investigate(
            @PathVariable Long id,
            @RequestBody CaseDtos.InvestigateRequest request) {
        if (request.locationKey() == null || request.locationKey().isBlank()) {
            throw new BusinessException("请选择调查地点");
        }
        return ApiResponse.ok(investigationService.investigate(id, UserContext.userId(), request));
    }

    @PostMapping("/{id}/puzzles/{puzzleId}")
    public ApiResponse<CaseDtos.PuzzleResult> solvePuzzle(
            @PathVariable Long id,
            @PathVariable Long puzzleId,
            @Valid @RequestBody CaseDtos.PuzzleAnswerRequest request) {
        return ApiResponse.ok(puzzleService.submit(id, puzzleId, UserContext.userId(), request));
    }

    /**
     * 证据板：玩家已发现的线索（节点）+ 自己建立的关联（连线）。
     */
    @GetMapping("/{id}/evidence-links")
    public ApiResponse<EvidenceDtos.EvidenceBoard> evidenceBoard(@PathVariable Long id) {
        return ApiResponse.ok(evidenceBoardService.board(id, UserContext.currentUserIdOrNull()));
    }

    /**
     * 在两条已发现的线索之间建立关联。后端只校验归属，不判断这个关联是否成立。
     */
    @PostMapping("/{id}/evidence-links")
    public ApiResponse<EvidenceDtos.EvidenceLinkView> createEvidenceLink(
            @PathVariable Long id,
            @RequestBody EvidenceDtos.CreateEvidenceLinkRequest request) {
        return ApiResponse.ok("关联已保存", evidenceBoardService.create(id, UserContext.userId(), request));
    }

    @DeleteMapping("/{id}/evidence-links/{linkId}")
    public ApiResponse<Void> deleteEvidenceLink(@PathVariable Long id, @PathVariable Long linkId) {
        evidenceBoardService.delete(id, UserContext.userId(), linkId);
        return ApiResponse.ok("关联已删除", null);
    }
}

