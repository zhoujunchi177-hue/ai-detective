package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.dto.CaseDtos;
import com.mindtrace.entity.CaseLocation;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.InvestigationRecord;
import com.mindtrace.entity.UserClue;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.CaseLocationMapper;
import com.mindtrace.mapper.ClueMapper;
import com.mindtrace.mapper.InvestigationRecordMapper;
import com.mindtrace.mapper.UserClueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InvestigationService {
    private final CaseQueryService caseQueryService;
    private final CaseLocationMapper locationMapper;
    private final ClueMapper clueMapper;
    private final UserClueMapper userClueMapper;
    private final InvestigationRecordMapper investigationRecordMapper;
    private final ClueUnlockService clueUnlockService;

    @Transactional
    public CaseDtos.InvestigateResult investigate(Long caseId, Long userId,
                                                  CaseDtos.InvestigateRequest request) {
        caseQueryService.requireCase(caseId);
        CaseLocation location = locationMapper.selectOne(Wrappers.<CaseLocation>lambdaQuery()
                .eq(CaseLocation::getCaseId, caseId)
                .eq(CaseLocation::getLocationKey, request.locationKey())
                .last("LIMIT 1"));
        if (location == null) {
            throw new BusinessException(404, "调查地点不存在");
        }

        // 节点状态由后端裁定：即使前端被绕过，也不能调查未解锁的地点。
        CaseDtos.LocationView before = caseQueryService.locationViews(caseId, userId).stream()
                .filter(view -> view.locationKey().equals(location.getLocationKey()))
                .findFirst()
                .orElse(null);
        if (before != null && "LOCKED".equals(before.status())) {
            throw new BusinessException(before.lockedReason() == null
                    ? "该地点尚未解锁" : before.lockedReason());
        }

        List<Clue> initialClues = clueUnlockService.unlockInitialClues(caseId, userId);
        InvestigationRecord record = new InvestigationRecord();
        record.setUserId(userId);
        record.setCaseId(caseId);
        record.setLocationId(location.getId());
        record.setActionType(StringUtils.hasText(request.action()) ? request.action() : "SEARCH");

        List<Clue> candidates = clueMapper.selectList(Wrappers.<Clue>lambdaQuery()
                .eq(Clue::getCaseId, caseId)
                .eq(Clue::getLocationKey, location.getLocationKey())
                .orderByDesc(Clue::getImportance));
        List<Clue> unlocked = new ArrayList<>(initialClues);
        unlocked.addAll(clueUnlockService.unlock(userId, caseId, candidates, "LOCATION"));

        String narrative;
        if (!unlocked.isEmpty()) {
            narrative = "你在" + location.getName() + "完成检查，发现 " + unlocked.size()
                    + " 条新线索。数据库已记录，AI 只能解释这些既有证据，不能临时生成事实。";
        } else {
            narrative = "你重新检查了" + location.getName()
                    + "。没有新的可验证内容，但已有记录仍可与其他时间点交叉比对。";
        }
        record.setResultText(narrative);
        record.setCreatedAt(LocalDateTime.now());
        investigationRecordMapper.insert(record);

        CaseDtos.Progress progress = caseQueryService.progress(caseId, userId);
        // 本次调查可能解锁了后续地点，因此重新计算该节点的最新状态返回给前端。
        CaseDtos.LocationView after = caseQueryService.locationViews(caseId, userId).stream()
                .filter(view -> view.locationKey().equals(location.getLocationKey()))
                .findFirst()
                .orElse(null);
        return new CaseDtos.InvestigateResult(after, narrative, unlocked, progress);
    }
}
