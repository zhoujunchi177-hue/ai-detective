package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.UserClue;
import com.mindtrace.mapper.ClueMapper;
import com.mindtrace.mapper.UserClueMapper;
import com.mindtrace.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClueUnlockService {
    private final ClueMapper clueMapper;
    private final UserClueMapper userClueMapper;
    private final UserMapper userMapper;

    @Transactional
    public List<Clue> unlockInitialClues(Long caseId, Long userId) {
        List<Clue> initial = clueMapper.selectList(Wrappers.<Clue>lambdaQuery()
                .eq(Clue::getCaseId, caseId)
                .eq(Clue::getUnlockCondition, "initial"));
        return unlock(userId, caseId, initial, "INITIAL");
    }

    @Transactional
    public List<Clue> unlock(Long userId, Long caseId, List<Clue> clues, String source) {
        if (clues == null || clues.isEmpty()) {
            return List.of();
        }
        List<Clue> result = new ArrayList<>();
        // 先锁住该用户行，把同一玩家的并发解锁串行化。否则两个并发请求会
        // 双双判定「未发现」后都去插入：普通 insert 撞唯一键失败，
        // INSERT IGNORE 则在唯一键上互等成死锁（实测两种都出现过）。
        userMapper.lockById(userId);
        LocalDateTime now = LocalDateTime.now();
        for (Clue clue : clues) {
            // 去重仍然交给唯一键（uk_user_clue）+ INSERT IGNORE，作为最后一道兜底。
            int inserted = userClueMapper.insertIgnore(userId, caseId, clue.getId(), now, source);
            if (inserted > 0) {
                result.add(clue);
            }
        }
        return result;
    }

    @Transactional
    public List<Clue> unlockByPuzzle(Long userId, Long caseId, Long clueId) {
        if (clueId == null) {
            return List.of();
        }
        Clue clue = clueMapper.selectById(clueId);
        if (clue == null || !caseId.equals(clue.getCaseId())) {
            return List.of();
        }
        return unlock(userId, caseId, List.of(clue), "PUZZLE");
    }

    @Transactional
    public List<Clue> unlockByNpcKeyword(Long userId, Long caseId, Long npcId, String question) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        List<Clue> clues = clueMapper.selectList(Wrappers.<Clue>lambdaQuery()
                .eq(Clue::getCaseId, caseId)
                .eq(Clue::getNpcId, npcId)
                .isNotNull(Clue::getKeyword));
        List<Clue> matched = clues.stream()
                .filter(clue -> question.contains(clue.getKeyword()))
                .toList();
        return unlock(userId, caseId, matched, "NPC");
    }

    public boolean hasClue(Long userId, Long clueId) {
        return userClueMapper.selectCount(Wrappers.<UserClue>lambdaQuery()
                .eq(UserClue::getUserId, userId)
                .eq(UserClue::getClueId, clueId)) > 0;
    }
}

