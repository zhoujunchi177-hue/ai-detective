package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

@Data
@TableName("npc")
public class Npc {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long caseId;
    private String npcKey;
    private String name;
    private String avatar;
    private String description;
    private String personality;
    private String identity;
    private String location;
    private String greeting;
    @JsonIgnore
    private String hiddenInformation;
    private String relationship;
    private String contentType;
    private LocalDateTime createdAt;
}
