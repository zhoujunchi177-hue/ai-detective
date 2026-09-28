package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("npc_knowledge")
public class NpcKnowledge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long npcId;
    private String knowledgeKey;
    private String topic;
    private String content;
    private String disclosureLevel;
    private LocalDateTime createdAt;
}

