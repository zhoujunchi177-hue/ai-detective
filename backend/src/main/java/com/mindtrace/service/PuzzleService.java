package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.dto.CaseDtos;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.Puzzle;
import com.mindtrace.entity.UserPuzzle;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.PuzzleMapper;
import com.mindtrace.mapper.UserMapper;
import com.mindtrace.mapper.UserPuzzleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PuzzleService {
    private final PuzzleMapper puzzleMapper;
    private final UserPuzzleMapper userPuzzleMapper;
    private final UserMapper userMapper;
    private final CaseQueryService caseQueryService;
    private final ClueUnlockService clueUnlockService;

    @Transactional
    public CaseDtos.PuzzleResult submit(Long caseId, Long puzzleId, Long userId,
                                        CaseDtos.PuzzleAnswerRequest request) {
        // 必须先加锁，再执行任何普通 SELECT。
        // MySQL 默认 REPEATABLE READ 的读视图是在事务内**第一条普通读**时建立的：
        // 如果先跑了 requireCase（普通 SELECT）再去抢锁，等锁的这段时间里另一个
        // 事务已经提交，可本事务的快照早已固定，后面仍然读到「没有记录」，
        // 于是照样插入并撞上 uk_user_puzzle —— 加了锁也不管用。
        userMapper.lockById(userId);
        caseQueryService.requireCase(caseId);
        Puzzle puzzle = puzzleMapper.selectById(puzzleId);
        if (puzzle == null || !caseId.equals(puzzle.getCaseId())) {
            throw new BusinessException(404, "谜题不存在");
        }

        UserPuzzle userPuzzle = userPuzzleMapper.selectOne(Wrappers.<UserPuzzle>lambdaQuery()
                .eq(UserPuzzle::getUserId, userId)
                .eq(UserPuzzle::getPuzzleId, puzzleId)
                .last("LIMIT 1"));
        boolean correct = canonical(puzzle.getType(), request.answer())
                .equals(canonical(puzzle.getType(), puzzle.getCorrectAnswer()));

        if (userPuzzle == null) {
            userPuzzle = new UserPuzzle();
            userPuzzle.setUserId(userId);
            userPuzzle.setPuzzleId(puzzleId);
            userPuzzle.setAttempts(0);
            userPuzzle.setCompleted(false);
        }
        userPuzzle.setAttempts(userPuzzle.getAttempts() + 1);
        userPuzzle.setAnswer(request.answer());
        List<Clue> unlocked = List.of();
        if (correct && !Boolean.TRUE.equals(userPuzzle.getCompleted())) {
            userPuzzle.setCompleted(true);
            userPuzzle.setCompletedAt(LocalDateTime.now());
            unlocked = clueUnlockService.unlockByPuzzle(userId, caseId, puzzle.getUnlockRewardClueId());
        }
        if (userPuzzle.getId() == null) {
            userPuzzleMapper.insert(userPuzzle);
        } else {
            userPuzzleMapper.updateById(userPuzzle);
        }

        String message = correct
                ? (unlocked.isEmpty() ? "排序正确，该谜题已完成。" : "排序正确，隐藏线索已解锁。")
                : "结果与现有档案不一致，请检查时间点或证据之间的顺序。";
        return new CaseDtos.PuzzleResult(correct, message, unlocked,
                caseQueryService.progress(caseId, userId));
    }

    // 归一化玩家答案：去首尾空格、转小写、去内部空格，允许宽松比对。
    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace(" ", "");
    }

    // EVIDENCE_LINK 与顺序无关，排序后再比较，避免玩家因选择顺序不同被判错。
    static String canonical(String type, String value) {
        String normalized = normalize(value);
        if ("EVIDENCE_LINK".equals(type)) {
            return java.util.Arrays.stream(normalized.split(","))
                    .filter(item -> !item.isBlank())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return normalized;
    }
}
