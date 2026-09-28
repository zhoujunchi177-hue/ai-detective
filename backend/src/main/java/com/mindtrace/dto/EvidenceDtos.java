package com.mindtrace.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 证据板（Evidence Board）相关 DTO。
 * <p>
 * 证据板记录的是**玩家的推理链**，不是案件事实。后端只负责校验「两端线索都属于本案
 * 且已被该玩家发现」，关系类型和备注由玩家填写。
 */
public final class EvidenceDtos {

    private EvidenceDtos() {
    }

    /** 证据板上的一个节点：玩家已发现线索的精简视图。 */
    public record EvidenceNode(
            Long clueId,
            String clueCode,
            String title,
            String type,
            Integer importance,
            String sourceType) {
    }

    /** 证据板上的一条连线，两端线索的摘要一起返回，前端不需要再查一遍。 */
    public record EvidenceLinkView(
            Long id,
            EvidenceNode from,
            EvidenceNode to,
            String relationType,
            String relationLabel,
            String note,
            LocalDateTime createdAt) {
    }

    /** 关系类型选项。由后端给出，避免前端硬编码的标签与后端校验规则漂移。 */
    public record RelationTypeOption(String value, String label, String hint) {
    }

    /**
     * 证据板全量数据。一次返回节点、连线和可选关系类型，
     * 前端只负责渲染，不需要自己拼装或推断。
     */
    public record EvidenceBoard(
            List<EvidenceNode> nodes,
            List<EvidenceLinkView> links,
            List<RelationTypeOption> relationTypes) {
    }

    public record CreateEvidenceLinkRequest(
            Long fromClueId,
            Long toClueId,
            String relationType,
            String note) {
    }
}
