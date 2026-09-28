package com.mindtrace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 证据板上的一条连线。
 * <p>
 * 这是玩家自己构建的推理链，不是案件事实：后端只校验两条线索属于本案且已被该玩家发现，
 * 关系的类型与备注完全由玩家填写，AI 只能解释它，不能替玩家断定它成立。
 */
@Data
@TableName("evidence_relationships")
public class EvidenceRelationship {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long caseId;
    private Long fromClueId;
    private Long toClueId;
    private String relationType;
    private String note;
    private LocalDateTime createdAt;
}
