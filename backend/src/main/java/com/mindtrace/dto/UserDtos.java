package com.mindtrace.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class UserDtos {

    private UserDtos() {
    }

    public record Profile(
            Long id,
            String username,
            String nickname,
            String avatar,
            Integer level,
            Integer exp,
            Integer nextLevelExp,
            Integer coins,
            Integer completedCases,
            Integer streakDays,
            Integer totalScore,
            LocalDateTime lastLoginAt,
            List<AchievementView> achievements,
            List<GameHistoryView> history) {
    }

    public record AchievementView(
            Long id,
            String code,
            String name,
            String description,
            String icon,
            /** COMMON / RARE / EPIC / LEGENDARY。前端按它上色与排序，不参与解锁判定。 */
            String rarity,
            Integer rewardExp,
            Integer rewardCoins,
            boolean unlocked,
            LocalDateTime unlockedAt) {
    }

    public record GameHistoryView(
            Long recordId,
            Long caseId,
            String caseTitle,
            String caseCode,
            Integer totalScore,
            String status,
            LocalDateTime completedAt) {
    }
}

