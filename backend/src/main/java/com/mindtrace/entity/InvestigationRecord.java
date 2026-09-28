package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("investigation_records")
public class InvestigationRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long caseId;
    private Long locationId;
    private String actionType;
    private String resultText;
    private LocalDateTime createdAt;
}

