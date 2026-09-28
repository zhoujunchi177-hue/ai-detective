package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.dto.EvidenceDtos;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.EvidenceRelationship;
import com.mindtrace.entity.UserClue;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.ClueMapper;
import com.mindtrace.mapper.EvidenceRelationshipMapper;
import com.mindtrace.mapper.UserClueMapper;
import com.mindtrace.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 证据板服务。
 * <p>
 * 职责边界：后端只校验「两端线索都属于本案且已被该玩家发现」，
 * 关系类型与备注由玩家填写。**后端不判断这个关联是否成立**——
 * 那是玩家推理的内容，AI 也只能解释它，不能替玩家下结论。
 */
@Service
@RequiredArgsConstructor
public class EvidenceBoardService {

    /** 关系类型白名单。顺序即前端展示顺序，前端不需要自己维护标签文案。 */
    static final List<EvidenceDtos.RelationTypeOption> RELATION_TYPES = List.of(
            new EvidenceDtos.RelationTypeOption("SUPPORTS", "互相印证", "两条线索指向同一个事实"),
            new EvidenceDtos.RelationTypeOption("CONTRADICTS", "互相矛盾", "两条线索在时间或事实上冲突"),
            new EvidenceDtos.RelationTypeOption("TIMELINE", "时间先后", "一条线索发生在另一条之前"),
            new EvidenceDtos.RelationTypeOption("IDENTITY", "指向同一对象", "两条线索指向同一个人或地点"));

    static final String DEFAULT_RELATION_TYPE = "SUPPORTS";

    /** 备注长度上限，与 evidence_relationships.note 的 VARCHAR(300) 保持一致。 */
    static final int MAX_NOTE_LENGTH = 300;

    private final CaseQueryService caseQueryService;
    private final ClueMapper clueMapper;
    private final UserClueMapper userClueMapper;
    private final UserMapper userMapper;
    private final EvidenceRelationshipMapper evidenceRelationshipMapper;

    public EvidenceDtos.EvidenceBoard board(Long caseId, Long userId) {
        caseQueryService.requireCase(caseId);
        if (userId == null) {
            return new EvidenceDtos.EvidenceBoard(List.of(), List.of(), RELATION_TYPES);
        }
        List<Clue> discovered = caseQueryService.discoveredClues(caseId, userId);
        Map<Long, Clue> clueById = discovered.stream()
                .collect(Collectors.toMap(Clue::getId, clue -> clue, (left, right) -> left));

        List<EvidenceDtos.EvidenceLinkView> links = evidenceRelationshipMapper
                .selectList(Wrappers.<EvidenceRelationship>lambdaQuery()
                        .eq(EvidenceRelationship::getUserId, userId)
                        .eq(EvidenceRelationship::getCaseId, caseId)
                        .orderByDesc(EvidenceRelationship::getCreatedAt))
                .stream()
                // 线索若已不在玩家的已发现集合里（例如数据被重置），跳过这条连线而不是报错。
                .map(link -> toView(link, clueById))
                .filter(Objects::nonNull)
                .toList();

        List<EvidenceDtos.EvidenceNode> nodes = discovered.stream().map(this::toNode).toList();
        return new EvidenceDtos.EvidenceBoard(nodes, links, RELATION_TYPES);
    }

    @Transactional
    public EvidenceDtos.EvidenceLinkView create(Long caseId, Long userId,
                                                EvidenceDtos.CreateEvidenceLinkRequest request) {
        // 必须先加锁，再执行任何普通 SELECT（原因同 PuzzleService.submit：
        // REPEATABLE READ 的读视图由事务内第一条普通读建立，先读后锁等于没锁）。
        // 下面的「查重 -> 插入」是 check-then-act：连点两次提交会双双查到「不重复」，
        // 然后都插入，后者撞 uk_evidence_pair。锁住用户行后，重复的一方会稳定地
        // 走到「已经连过了」这条明确提示上。
        userMapper.lockById(userId);
        caseQueryService.requireCase(caseId);

        String relationType = normalizeRelationType(request.relationType());
        String note = normalizeNote(request.note());
        requireValidPair(request.fromClueId(), request.toClueId());

        Set<Long> discoveredIds = userClueMapper.selectList(Wrappers.<UserClue>lambdaQuery()
                        .eq(UserClue::getUserId, userId)
                        .eq(UserClue::getCaseId, caseId))
                .stream()
                .map(UserClue::getClueId)
                .collect(Collectors.toSet());
        List<Clue> pair = clueMapper.selectBatchIds(
                List.of(request.fromClueId(), request.toClueId()));
        requireSameCaseAndDiscovered(caseId, discoveredIds, pair, request);

        long duplicate = evidenceRelationshipMapper.selectCount(Wrappers.<EvidenceRelationship>lambdaQuery()
                .eq(EvidenceRelationship::getUserId, userId)
                // 同一对线索不分方向，A→B 和 B→A 视为同一条关联
                .and(wrapper -> wrapper
                        .nested(inner -> inner
                                .eq(EvidenceRelationship::getFromClueId, request.fromClueId())
                                .eq(EvidenceRelationship::getToClueId, request.toClueId()))
                        .or()
                        .nested(inner -> inner
                                .eq(EvidenceRelationship::getFromClueId, request.toClueId())
                                .eq(EvidenceRelationship::getToClueId, request.fromClueId()))));
        if (duplicate > 0) {
            throw new BusinessException("这两条线索已经连过了");
        }

        EvidenceRelationship link = new EvidenceRelationship();
        link.setUserId(userId);
        link.setCaseId(caseId);
        link.setFromClueId(request.fromClueId());
        link.setToClueId(request.toClueId());
        link.setRelationType(relationType);
        link.setNote(note);
        link.setCreatedAt(LocalDateTime.now());
        evidenceRelationshipMapper.insert(link);

        Map<Long, Clue> clueById = pair.stream()
                .collect(Collectors.toMap(Clue::getId, clue -> clue, (left, right) -> left));
        EvidenceDtos.EvidenceLinkView view = toView(link, clueById);
        if (view == null) {
            throw new BusinessException(500, "关联已保存，但线索摘要读取失败");
        }
        return view;
    }

    @Transactional
    public void delete(Long caseId, Long userId, Long linkId) {
        caseQueryService.requireCase(caseId);
        EvidenceRelationship link = evidenceRelationshipMapper.selectById(linkId);
        // 不区分「不存在」和「不属于你」，避免用错误信息探测别人的数据。
        if (link == null || !Objects.equals(link.getUserId(), userId)
                || !Objects.equals(link.getCaseId(), caseId)) {
            throw new BusinessException(404, "关联不存在");
        }
        evidenceRelationshipMapper.deleteById(linkId);
    }

    // ------------------------------------------------------------------
    // 纯函数：便于直接单测，不依赖任何 mapper
    // ------------------------------------------------------------------

    /** 关系类型归一化：空白回退为默认值，未知类型直接拒绝（不静默改写成默认值）。 */
    static String normalizeRelationType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DEFAULT_RELATION_TYPE;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        boolean known = RELATION_TYPES.stream().anyMatch(option -> option.value().equals(value));
        if (!known) {
            throw new BusinessException("不支持的关系类型：" + raw);
        }
        return value;
    }

    /** 备注清洗：去空白，超长直接拒绝而不是静默截断（截断会悄悄丢掉玩家写的内容）。 */
    static String normalizeNote(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String note = raw.strip();
        if (note.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException("备注不能超过 " + MAX_NOTE_LENGTH + " 个字符");
        }
        return note;
    }

    static void requireValidPair(Long fromClueId, Long toClueId) {
        if (fromClueId == null || toClueId == null) {
            throw new BusinessException("请选择两条线索");
        }
        if (fromClueId.equals(toClueId)) {
            throw new BusinessException("不能把一条线索连到它自己");
        }
    }

    static void requireSameCaseAndDiscovered(Long caseId, Set<Long> discoveredIds,
                                             List<Clue> pair,
                                             EvidenceDtos.CreateEvidenceLinkRequest request) {
        if (pair.size() != 2) {
            throw new BusinessException(404, "线索不存在");
        }
        boolean allInCase = pair.stream().allMatch(clue -> caseId.equals(clue.getCaseId()));
        if (!allInCase) {
            throw new BusinessException("线索不属于当前案件");
        }
        if (!discoveredIds.contains(request.fromClueId())
                || !discoveredIds.contains(request.toClueId())) {
            throw new BusinessException("只能关联你已发现的线索");
        }
    }

    static String relationLabel(String relationType) {
        return RELATION_TYPES.stream()
                .filter(option -> option.value().equals(relationType))
                .map(EvidenceDtos.RelationTypeOption::label)
                .findFirst()
                .orElse(relationType);
    }

    // ------------------------------------------------------------------

    private EvidenceDtos.EvidenceNode toNode(Clue clue) {
        return new EvidenceDtos.EvidenceNode(
                clue.getId(), clue.getClueCode(), clue.getTitle(),
                clue.getType(), clue.getImportance(), clue.getSourceType());
    }

    /** 两端线索任一不可见时返回 {@code null}，由调用方决定是跳过还是报错。 */
    private EvidenceDtos.EvidenceLinkView toView(EvidenceRelationship link, Map<Long, Clue> clueById) {
        Clue from = clueById.get(link.getFromClueId());
        Clue to = clueById.get(link.getToClueId());
        if (from == null || to == null) {
            return null;
        }
        return new EvidenceDtos.EvidenceLinkView(
                link.getId(),
                toNode(from),
                toNode(to),
                link.getRelationType(),
                relationLabel(link.getRelationType()),
                link.getNote(),
                link.getCreatedAt());
    }
}
