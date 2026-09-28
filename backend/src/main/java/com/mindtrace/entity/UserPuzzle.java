package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_puzzles")
public class UserPuzzle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long puzzleId;
    private Boolean completed;
    private Integer attempts;
    private String answer;
    private LocalDateTime completedAt;
}

