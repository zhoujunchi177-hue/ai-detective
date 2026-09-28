package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("game_records")
public class GameRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long caseId;
    private String status;
    private Integer investigationScore;
    private Integer clueScore;
    private Integer timelineScore;
    private Integer logicScore;
    private Integer totalScore;
    private Integer expReward;
    private Integer coinReward;
    private String hypothesis;
    private String keyPeople;
    private String keyTimeline;
    private String evidenceClueIds;
    private String reasoningText;
    private String conclusion;
    private String aiReport;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

