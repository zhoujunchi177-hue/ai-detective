package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

@Data
@TableName("puzzles")
public class Puzzle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String puzzleKey;
    private String title;
    private String description;
    private String type;
    private String payload;
    @JsonIgnore
    private String correctAnswer;
    @JsonIgnore
    private Long unlockRewardClueId;
    private Integer importance;
    private LocalDateTime createdAt;
}
