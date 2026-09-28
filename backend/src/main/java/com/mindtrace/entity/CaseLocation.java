package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("case_locations")
public class CaseLocation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String locationKey;
    private String name;
    private String description;
    private String icon;
    private Integer mapX;
    private Integer mapY;
    private String unlockCondition;
    private LocalDateTime createdAt;
}

