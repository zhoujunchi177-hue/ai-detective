package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("cases")
public class CaseFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseCode;
    private String title;
    private String subtitle;
    private String realName;
    private String summary;
    private String description;
    private String caseType;
    private String difficulty;
    private String era;
    private String location;
    private String coverUrl;
    private String status;
    private Integer completion;
    private Integer players;
    private Integer contentRating;
    private BigDecimal realRatio;
    private BigDecimal adaptedRatio;
    private BigDecimal fictionalRatio;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

