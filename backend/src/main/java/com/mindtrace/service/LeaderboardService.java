package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.dto.RankingDtos;
import com.mindtrace.entity.LeaderboardEntry;
import com.mindtrace.entity.User;
import com.mindtrace.mapper.LeaderboardEntryMapper;
import com.mindtrace.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaderboardService {
    private final UserMapper userMapper;
    private final LeaderboardEntryMapper leaderboardEntryMapper;

    public RankingDtos.RankingBoard ranking(String type, Long currentUserId) {
        String normalized = switch (type == null ? "score" : type) {
            case "cases" -> "cases";
            case "level" -> "level";
            default -> "score";
        };

        var query = Wrappers.<User>lambdaQuery();
        if ("cases".equals(normalized)) {
            query.orderByDesc(User::getCompletedCases).orderByDesc(User::getTotalScore);
        } else if ("level".equals(normalized)) {
            query.orderByDesc(User::getLevel).orderByDesc(User::getExp);
        } else {
            query.orderByDesc(User::getTotalScore).orderByDesc(User::getCompletedCases).orderByDesc(User::getExp);
        }

        List<User> users = userMapper.selectList(query.last("LIMIT 50"));
        List<RankingDtos.Entry> entries = java.util.stream.IntStream.range(0, users.size())
                .mapToObj(index -> {
                    User user = users.get(index);
                    return new RankingDtos.Entry(
                            index + 1,
                            user.getId(),
                            user.getNickname(),
                            user.getAvatar(),
                            user.getLevel(),
                            user.getExp(),
                            user.getCompletedCases(),
                            user.getTotalScore(),
                            currentUserId != null && currentUserId.equals(user.getId()));
                }).toList();

        String title = switch (normalized) {
            case "cases" -> "案件完成榜";
            case "level" -> "调查等级榜";
            default -> "总分排行榜";
        };
        return new RankingDtos.RankingBoard(normalized, title, entries);
    }

    @Transactional
    public void syncUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        upsert(user, "score", user.getTotalScore());
        upsert(user, "cases", user.getCompletedCases());
        upsert(user, "level", user.getLevel());
    }

    private void upsert(User user, String rankType, int value) {
        LeaderboardEntry existing = leaderboardEntryMapper.selectOne(Wrappers.<LeaderboardEntry>lambdaQuery()
                .eq(LeaderboardEntry::getUserId, user.getId())
                .eq(LeaderboardEntry::getRankType, rankType)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new LeaderboardEntry();
            existing.setUserId(user.getId());
            existing.setRankType(rankType);
        }
        existing.setRankPosition(0);
        if ("cases".equals(rankType)) {
            existing.setCompletedCases(value);
            existing.setTotalScore(user.getTotalScore());
            existing.setLevel(user.getLevel());
        } else if ("level".equals(rankType)) {
            existing.setLevel(user.getLevel());
            existing.setTotalScore(user.getTotalScore());
            existing.setCompletedCases(user.getCompletedCases());
        } else {
            existing.setTotalScore(value);
            existing.setCompletedCases(user.getCompletedCases());
            existing.setLevel(user.getLevel());
        }
        existing.setUpdatedAt(now);
        if (existing.getId() == null) {
            leaderboardEntryMapper.insert(existing);
        } else {
            leaderboardEntryMapper.updateById(existing);
        }
    }
}
