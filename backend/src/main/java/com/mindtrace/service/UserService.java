package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.dto.UserDtos;
import com.mindtrace.entity.Achievement;
import com.mindtrace.entity.CaseFile;
import com.mindtrace.entity.GameRecord;
import com.mindtrace.entity.User;
import com.mindtrace.entity.UserAchievement;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.AchievementMapper;
import com.mindtrace.mapper.CaseFileMapper;
import com.mindtrace.mapper.GameRecordMapper;
import com.mindtrace.mapper.UserAchievementMapper;
import com.mindtrace.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserMapper userMapper;
    private final AchievementMapper achievementMapper;
    private final UserAchievementMapper userAchievementMapper;
    private final GameRecordMapper gameRecordMapper;
    private final CaseFileMapper caseFileMapper;

    public User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    public UserDtos.Profile profile(Long userId) {
        User user = requireUser(userId);
        List<Achievement> achievements = achievementMapper.selectList(
                Wrappers.<Achievement>lambdaQuery().orderByAsc(Achievement::getId));
        List<UserAchievement> unlocked = userAchievementMapper.selectList(
                Wrappers.<UserAchievement>lambdaQuery().eq(UserAchievement::getUserId, userId));
        Map<Long, UserAchievement> unlockedMap = new HashMap<>();
        unlocked.forEach(item -> unlockedMap.put(item.getAchievementId(), item));

        List<UserDtos.AchievementView> achievementViews = achievements.stream()
                .map(item -> new UserDtos.AchievementView(
                        item.getId(),
                        item.getCode(),
                        item.getName(),
                        item.getDescription(),
                        item.getIcon(),
                        AchievementService.normalizeRarity(item.getRarity()),
                        item.getRewardExp(),
                        item.getRewardCoins(),
                        unlockedMap.containsKey(item.getId()),
                        unlockedMap.containsKey(item.getId()) ? unlockedMap.get(item.getId()).getUnlockedAt() : null))
                .toList();

        List<GameRecord> records = gameRecordMapper.selectList(Wrappers.<GameRecord>lambdaQuery()
                .eq(GameRecord::getUserId, userId)
                .orderByDesc(GameRecord::getCompletedAt)
                .orderByDesc(GameRecord::getUpdatedAt));
        Map<Long, CaseFile> caseMap = new HashMap<>();
        if (!records.isEmpty()) {
            caseFileMapper.selectBatchIds(records.stream().map(GameRecord::getCaseId).distinct().toList())
                    .forEach(caseFile -> caseMap.put(caseFile.getId(), caseFile));
        }

        List<UserDtos.GameHistoryView> history = records.stream().map(record -> {
            CaseFile caseFile = caseMap.get(record.getCaseId());
            return new UserDtos.GameHistoryView(
                    record.getId(),
                    record.getCaseId(),
                    caseFile == null ? "未知案件" : caseFile.getTitle(),
                    caseFile == null ? "-" : caseFile.getCaseCode(),
                    record.getTotalScore(),
                    record.getStatus(),
                    record.getCompletedAt());
        }).toList();

        return new UserDtos.Profile(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatar(),
                user.getLevel(),
                user.getExp(),
                user.getLevel() * 500,
                user.getCoins(),
                user.getCompletedCases(),
                user.getStreakDays(),
                user.getTotalScore(),
                user.getLastLoginAt(),
                achievementViews,
                history);
    }
}

