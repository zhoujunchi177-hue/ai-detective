package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("case_timeline")
public class CaseTimeline {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private LocalDateTime eventTime;
    private String eventDateText;
    private String title;
    private String description;
    private String people;
    private String location;
    private String sourceName;
    private String sourceUrl;
    private String contentType;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}

