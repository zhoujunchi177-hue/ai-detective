package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.entity.Achievement;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.Puzzle;
import com.mindtrace.entity.User;
import com.mindtrace.entity.UserAchievement;
import com.mindtrace.entity.UserClue;
import com.mindtrace.entity.UserPuzzle;
import com.mindtrace.mapper.AchievementMapper;
import com.mindtrace.mapper.ClueMapper;
import com.mindtrace.mapper.PuzzleMapper;
import com.mindtrace.mapper.UserAchievementMapper;
import com.mindtrace.mapper.UserClueMapper;
import com.mindtrace.mapper.UserMapper;
import com.mindtrace.mapper.UserPuzzleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AchievementService {
    private static final String DEFAULT_RARITY = "COMMON";
    private static final Set<String> KNOWN_RARITIES = Set.of("COMMON", "RARE", "EPIC", "LEGENDARY");

    private final AchievementMapper achievementMapper;
    private final UserAchievementMapper userAchievementMapper;
    private final UserClueMapper userClueMapper;
    private final ClueMapper clueMapper;
    private final UserPuzzleMapper userPuzzleMapper;
    private final PuzzleMapper puzzleMapper;
    private final UserMapper userMapper;

    /**
     * 判定一枚徽章是否该解锁的**全部输入**。
     * 刻意做成一个显式的值对象：条件判定因此变成纯函数，可以零 mock 直接测
     * （Java 25 + byte-buddy 1.15.11 下 Mockito 的 inline mock 不可用）。
     */
    record AchievementFacts(
            int completedCases,
            long hiddenClues,
            long contradictionClues,
            long solvedTimeSortPuzzles) {
    }

    /**
     * 解锁条件表。这是唯一的真相来源 —— 数据库只存文案与奖励，条件写在这里。
     *
     * 注意：每枚徽章的条件必须**互不相同**。此前 TIMELINE_MASTER 的条件被写成了
     * 和 FIRST_CASE 一样的 `completedCases >= 1`，结果第一次结案就同时解锁两枚，
     * 「时间线复核者」名不副实。现在它要求真的完成过时间线排序谜题。
     */
    static boolean shouldUnlock(String code, AchievementFacts facts) {
        return switch (code) {
            case "FIRST_CASE" -> facts.completedCases() >= 1;
            case "FIRST_HIDDEN" -> facts.hiddenClues() >= 1;
            case "KEY_CONTRADICTION" -> facts.contradictionClues() >= 1;
            case "TIMELINE_MASTER" -> facts.solvedTimeSortPuzzles() >= 1;
            default -> false;
        };
    }

    /** 数据库里 rarity 可能是 NULL 或空串（老数据、手工插入），统一兜底成 COMMON。 */
    static String normalizeRarity(String rarity) {
        if (rarity == null || rarity.isBlank()) {
            return DEFAULT_RARITY;
        }
        String normalized = rarity.trim().toUpperCase(Locale.ROOT);
        return KNOWN_RARITIES.contains(normalized) ? normalized : DEFAULT_RARITY;
    }

    @Transactional
    public List<String> evaluateAndUnlock(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return List.of();
        }

        AchievementFacts facts = collectFacts(userId, user);
        List<String> newlyUnlocked = new ArrayList<>();
        List<Achievement> achievements = achievementMapper.selectList(Wrappers.lambdaQuery());
        for (Achievement achievement : achievements) {
            if (!shouldUnlock(achievement.getCode(), facts) || isUnlocked(userId, achievement.getId())) {
                continue;
            }

            UserAchievement item = new UserAchievement();
            item.setUserId(userId);
            item.setAchievementId(achievement.getId());
            item.setUnlockedAt(LocalDateTime.now());
            userAchievementMapper.insert(item);

            user.setExp((user.getExp() == null ? 0 : user.getExp()) + safe(achievement.getRewardExp()));
            user.setCoins((user.getCoins() == null ? 0 : user.getCoins()) + safe(achievement.getRewardCoins()));
            user.setLevel(1 + user.getExp() / 500);
            userMapper.updateById(user);
            newlyUnlocked.add(achievement.getName());
        }
        return newlyUnlocked;
    }

    /** 一次把判定所需的四个事实全查出来，避免每枚徽章各查一遍。 */
    private AchievementFacts collectFacts(Long userId, User user) {
        List<Long> clueIds = userClueMapper.selectList(Wrappers.<UserClue>lambdaQuery()
                        .eq(UserClue::getUserId, userId))
                .stream()
                .map(UserClue::getClueId)
                .toList();

        long hiddenClues = 0;
        long contradictionClues = 0;
        if (!clueIds.isEmpty()) {
            hiddenClues = clueMapper.selectCount(Wrappers.<Clue>lambdaQuery()
                    .in(Clue::getId, clueIds)
                    .eq(Clue::getIsHidden, true));
            contradictionClues = clueMapper.selectCount(Wrappers.<Clue>lambdaQuery()
                    .in(Clue::getId, clueIds)
                    .eq(Clue::getType, "CONTRADICTION"));
        }

        return new AchievementFacts(
                user.getCompletedCases() == null ? 0 : user.getCompletedCases(),
                hiddenClues,
                contradictionClues,
                countSolvedTimeSortPuzzles(userId));
    }

    private long countSolvedTimeSortPuzzles(Long userId) {
        List<Long> solvedPuzzleIds = userPuzzleMapper.selectList(Wrappers.<UserPuzzle>lambdaQuery()
                        .eq(UserPuzzle::getUserId, userId)
                        .eq(UserPuzzle::getCompleted, true))
                .stream()
                .map(UserPuzzle::getPuzzleId)
                .toList();
        if (solvedPuzzleIds.isEmpty()) {
            return 0;
        }
        return puzzleMapper.selectCount(Wrappers.<Puzzle>lambdaQuery()
                .in(Puzzle::getId, solvedPuzzleIds)
                .eq(Puzzle::getType, "TIME_SORT"));
    }

    private boolean isUnlocked(Long userId, Long achievementId) {
        return userAchievementMapper.selectCount(Wrappers.<UserAchievement>lambdaQuery()
                .eq(UserAchievement::getUserId, userId)
                .eq(UserAchievement::getAchievementId, achievementId)) > 0;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
