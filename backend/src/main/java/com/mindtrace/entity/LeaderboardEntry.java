package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("leaderboard_entries")
public class LeaderboardEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String rankType;
    private Integer rankPosition;
    private Integer totalScore;
    private Integer completedCases;
    private Integer level;
    private LocalDateTime updatedAt;
}

