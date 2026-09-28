package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("suspects")
public class Suspect {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String name;
    private String alias;
    private String role;
    private String description;
    private String relationship;
    private String status;
    private String evidenceLevel;
    private String contentType;
    private String avatar;
    private LocalDateTime createdAt;
}

