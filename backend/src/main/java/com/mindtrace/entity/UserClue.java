package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_clues")
public class UserClue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long caseId;
    private Long clueId;
    private LocalDateTime discoveredAt;
    private String source;
}

