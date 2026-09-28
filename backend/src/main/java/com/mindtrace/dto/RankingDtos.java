package com.mindtrace.dto;

public final class RankingDtos {

    private RankingDtos() {
    }

    public record Entry(
            int rank,
            Long userId,
            String nickname,
            String avatar,
            int level,
            int exp,
            int completedCases,
            int totalScore,
            boolean currentUser) {
    }

    public record RankingBoard(
            String type,
            String title,
            java.util.List<Entry> entries) {
    }
}

