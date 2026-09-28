package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindtrace.agent.AgentAnswer;
import com.mindtrace.agent.AgentService;
import com.mindtrace.dto.AgentDtos;
import com.mindtrace.dto.CaseDtos;
import com.mindtrace.entity.GameRecord;
import com.mindtrace.entity.User;
import com.mindtrace.mapper.GameRecordMapper;
import com.mindtrace.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GameService {
    private final CaseQueryService caseQueryService;
    private final GameRecordMapper gameRecordMapper;
    private final UserMapper userMapper;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final AchievementService achievementService;
    private final LeaderboardService leaderboardService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 结案提交。
     *
     * <p>刻意**不加** {@code @Transactional}：{@code agentService.finalAnalysis} 是真实 AI 调用，
     * 耗时几秒到几十秒，若整方法带事务，这段时间会一直占着一条数据库连接 —— 并发提交时
     * 连接池会被拖垮。因此拆成三段：只读准备 → AI 调用（事务外）→ 落库（显式开事务）。
     *
     * <p>落库段必须整体原子：发奖励、累加用户统计、判定成就、同步排行榜是一组；
     * 而且 {@code AchievementService.evaluateAndUnlock} 要读到本事务内刚更新的
     * {@code user.completedCases}，所以它必须与用户更新处于同一事务（REQUIRED 会加入）。
     */
    public AgentDtos.SubmitResult submit(Long caseId, Long userId, AgentDtos.SubmitRequest request) {
        // ---- 第一段：只读准备（不占写事务）----
        CaseDtos.CaseDetail detail = caseQueryService.detail(caseId, userId);
        List<Long> evidence = request.evidenceClueIds() == null ? List.of() : request.evidenceClueIds();
        long validEvidence = detail.discoveredClues().stream()
                .filter(clue -> evidence.contains(clue.getId()))
                .count();

        int investigationScore = ratioScore(30, detail.progress().investigatedLocations(),
                detail.progress().totalLocations());
        int clueScore = ratioScore(25, detail.progress().discoveredClues(),
                detail.progress().totalDiscoverableClues());
        int timelineScore = ratioScore(20, detail.progress().solvedPuzzles(),
                detail.progress().totalPuzzles());
        int logicScore = logicScore(request, validEvidence);
        int totalScore = investigationScore + clueScore + timelineScore + logicScore;
        String submission = buildSubmissionText(request, evidence);

        // ---- 第二段：AI 结案分析（最慢，必须在事务外）----
        AgentAnswer report = agentService.finalAnalysis(userId, caseId, submission);
        String normalizedReport = normalizeReport(report.content());

        // ---- 第三段：落库（事务内，快）----
        return transactionTemplate.execute(status -> persist(
                caseId, userId, request, evidence,
                investigationScore, clueScore, timelineScore, logicScore, totalScore,
                normalizedReport, report));
    }

    /** 落库段：发奖励 + 累加统计 + 判定成就 + 同步排行榜，必须整体成功或整体回滚。 */
    private AgentDtos.SubmitResult persist(Long caseId, Long userId, AgentDtos.SubmitRequest request,
                                           List<Long> evidence,
                                           int investigationScore, int clueScore, int timelineScore,
                                           int logicScore, int totalScore,
                                           String normalizedReport, AgentAnswer report) {
        GameRecord existing = gameRecordMapper.selectOne(Wrappers.<GameRecord>lambdaQuery()
                .eq(GameRecord::getUserId, userId)
                .eq(GameRecord::getCaseId, caseId)
                .last("LIMIT 1"));
        boolean firstCompletion = existing == null || !"COMPLETED".equals(existing.getStatus());
        int oldTotal = existing == null || existing.getTotalScore() == null ? 0 : existing.getTotalScore();
        int finalScore = Math.max(oldTotal, totalScore);

        int expReward = firstCompletion ? finalScore * 2 + (finalScore >= 80 ? 60 : 0) : 0;
        int coinReward = firstCompletion ? finalScore + (finalScore >= 80 ? 30 : 0) : 0;

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new GameRecord();
            existing.setUserId(userId);
            existing.setCaseId(caseId);
            existing.setCreatedAt(now);
        }
        existing.setStatus("COMPLETED");
        existing.setInvestigationScore(investigationScore);
        existing.setClueScore(clueScore);
        existing.setTimelineScore(timelineScore);
        existing.setLogicScore(logicScore);
        existing.setTotalScore(finalScore);
        existing.setExpReward((existing.getExpReward() == null ? 0 : existing.getExpReward()) + expReward);
        existing.setCoinReward((existing.getCoinReward() == null ? 0 : existing.getCoinReward()) + coinReward);
        existing.setHypothesis(request.hypothesis());
        existing.setKeyPeople(request.keyPeople());
        existing.setKeyTimeline(request.keyTimeline());
        existing.setEvidenceClueIds(joinIds(evidence));
        existing.setReasoningText(request.reasoningText());
        existing.setConclusion(request.conclusion());
        existing.setAiReport(normalizedReport);
        existing.setCompletedAt(now);
        existing.setUpdatedAt(now);
        if (existing.getId() == null) {
            gameRecordMapper.insert(existing);
        } else {
            gameRecordMapper.updateById(existing);
        }

        User user = userMapper.selectById(userId);
        if (firstCompletion) {
            user.setExp(safe(user.getExp()) + expReward);
            user.setCoins(safe(user.getCoins()) + coinReward);
            user.setCompletedCases(safe(user.getCompletedCases()) + 1);
            user.setTotalScore(safe(user.getTotalScore()) + finalScore);
            user.setLevel(1 + safe(user.getExp()) / 500);
            user.setUpdatedAt(now);
            userMapper.updateById(user);
        } else if (finalScore > oldTotal) {
            user.setTotalScore(safe(user.getTotalScore()) + (finalScore - oldTotal));
            user.setUpdatedAt(now);
            userMapper.updateById(user);
        }

        List<String> achievements = achievementService.evaluateAndUnlock(userId);
        leaderboardService.syncUser(userId);

        return new AgentDtos.SubmitResult(
                existing.getId(),
                investigationScore,
                clueScore,
                timelineScore,
                logicScore,
                finalScore,
                expReward,
                coinReward,
                rating(finalScore),
                normalizedReport,
                report.aiAvailable(),
                report.notice(),
                achievements);
    }

    private int ratioScore(int max, int current, int total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.round(max * Math.min(1.0, current * 1.0 / total));
    }

    private int logicScore(AgentDtos.SubmitRequest request, long validEvidence) {
        int score = 0;
        int textLength = safeLength(request.hypothesis()) + safeLength(request.reasoningText())
                + safeLength(request.conclusion());
        if (textLength >= 180) {
            score += 8;
        } else if (textLength >= 70) {
            score += 4;
        }
        score += (int) Math.min(8, validEvidence);
        if (safeLength(request.keyTimeline()) >= 10) {
            score += 5;
        }
        if (safeLength(request.conclusion()) >= 25) {
            score += 4;
        }
        return Math.min(25, score);
    }

    private String normalizeReport(String content) {
        try {
            JsonNode json = objectMapper.readTree(content);
            if (!json.isObject()) {
                throw new IllegalArgumentException("not object");
            }
            return json.toString();
        } catch (Exception exception) {
            try {
                return objectMapper.writeValueAsString(java.util.Map.of(
                        "summary", "结案记录已保存。",
                        "raw", content,
                        "realityNotice", "【现实案件资料】游戏推理不代表现实案件结论。"));
            } catch (Exception ignored) {
                return "{\"summary\":\"结案记录已保存。\"}";
            }
        }
    }

    private String buildSubmissionText(AgentDtos.SubmitRequest request, List<Long> evidence) {
        return "核心假设：" + request.hypothesis()
                + "\n关键人物：" + request.keyPeople()
                + "\n关键时间线：" + request.keyTimeline()
                + "\n引用线索ID：" + joinIds(evidence)
                + "\n推理过程：" + request.reasoningText()
                + "\n最终结论：" + request.conclusion();
    }

    private String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String rating(int score) {
        if (score >= 90) {
            return "S · 档案级调查员";
        }
        if (score >= 75) {
            return "A · 高级调查员";
        }
        if (score >= 60) {
            return "B · 合格调查员";
        }
        return "C · 实习调查员";
    }
}

