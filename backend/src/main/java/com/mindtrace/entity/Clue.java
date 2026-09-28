package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("clues")
public class Clue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String clueCode;
    private String title;
    private String content;
    private String type;
    private Integer importance;
    private String sourceType;
    private String sourceName;
    private String sourceUrl;
    private String unlockCondition;
    private String locationKey;
    private Long npcId;
    private String keyword;
    private Boolean isReal;
    private Boolean isHidden;
    private LocalDateTime createdAt;
}

