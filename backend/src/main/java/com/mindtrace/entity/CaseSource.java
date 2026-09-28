package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("case_sources")
public class CaseSource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String sourceName;
    private String sourceUrl;
    private String sourceType;
    private LocalDate publishedAt;
    private String description;
    private String sourceReliability;
    private LocalDateTime createdAt;
}

