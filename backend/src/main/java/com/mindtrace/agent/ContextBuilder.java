package com.mindtrace.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.entity.CaseTimeline;
import com.mindtrace.entity.ChatMessage;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.Npc;
import com.mindtrace.entity.NpcKnowledge;
import com.mindtrace.entity.Suspect;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.CaseFileMapper;
import com.mindtrace.mapper.CaseTimelineMapper;
import com.mindtrace.mapper.ClueMapper;
import com.mindtrace.mapper.NpcKnowledgeMapper;
import com.mindtrace.mapper.NpcMapper;
import com.mindtrace.mapper.SuspectMapper;
import com.mindtrace.service.CaseQueryService;
import com.mindtrace.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ContextBuilder {
    private final UserService userService;
    private final CaseQueryService caseQueryService;
    private final CaseFileMapper caseFileMapper;
    private final CaseTimelineMapper timelineMapper;
    private final ClueMapper clueMapper;
    private final NpcMapper npcMapper;
    private final NpcKnowledgeMapper npcKnowledgeMapper;
    private final SuspectMapper suspectMapper;
    private final ConversationMemory conversationMemory;

    public AgentContext buildNpcContext(Long userId, Long caseId, Long npcId, String question) {
        Npc npc = npcMapper.selectById(npcId);
        if (npc == null || !caseId.equals(npc.getCaseId())) {
            throw new BusinessException(404, "NPC 不存在");
        }
        return build(userId, caseId, npc, question);
    }

    public AgentContext buildReasoningContext(Long userId, Long caseId, String hypothesis) {
        return build(userId, caseId, null, hypothesis);
    }

    private AgentContext build(Long userId, Long caseId, Npc npc, String question) {
        var caseFile = caseQueryService.requireCase(caseId);
        var user = userService.requireUser(userId);
        List<CaseTimeline> timeline = timelineMapper.selectList(Wrappers.<CaseTimeline>lambdaQuery()
                .eq(CaseTimeline::getCaseId, caseId)
                .orderByAsc(CaseTimeline::getSortOrder));
        List<Clue> discovered = caseQueryService.discoveredClues(caseId, userId);
        var discoveredIds = discovered.stream().map(Clue::getId).collect(Collectors.toSet());
        List<String> missingTopics = clueMapper.selectList(Wrappers.<Clue>lambdaQuery()
                        .eq(Clue::getCaseId, caseId)
                        .orderByDesc(Clue::getImportance))
                .stream()
                .filter(clue -> !discoveredIds.contains(clue.getId()))
                .map(Clue::getType)
                .distinct()
                .toList();
        List<NpcKnowledge> knowledge = npc == null
                ? List.of()
                : npcKnowledgeMapper.selectList(Wrappers.<NpcKnowledge>lambdaQuery()
                .eq(NpcKnowledge::getNpcId, npc.getId())
                .eq(NpcKnowledge::getDisclosureLevel, "normal")
                .orderByAsc(NpcKnowledge::getId));
        List<Suspect> suspects = suspectMapper.selectList(Wrappers.<Suspect>lambdaQuery()
                .eq(Suspect::getCaseId, caseId)
                .orderByAsc(Suspect::getId));
        List<ChatMessage> messages = npc == null ? List.of()
                : conversationMemory.recent(userId, caseId, npc.getId());
        return new AgentContext(user, caseFile, npc, question, timeline, discovered, missingTopics,
                knowledge, suspects, stripCurrentQuestion(messages, question));
    }

    /**
     * 去掉历史里「本轮提问」的重复副本 —— 提问现在被发给模型两次。
     * <p>
     * 成因：{@code ChatService.chat()} 在调用 AI <b>之前</b>就把玩家提问落库了
     * （{@link ConversationMemory#saveImmediately}，独立事务，否则玩家一刷新就丢问题）。
     * 于是 {@code recent()} 拿到的最后一条**就是本轮提问本身**，
     * 而 {@code PromptBuilder.npcDialoguePrompt()} 又会把
     * {@code context.playerQuestion()} 作为最后一条消息追加一次。
     * <p>
     * <b>收益只有「少发一份提问」，很小</b>：2026-09-21 实测（固定问题序列、8 轮、米娅·托雷斯），
     * 提问平均 13 字，约 7~9 token/轮。保留它的理由是**信息量不变、行为不变**，
     * 纯属去掉冗余 —— 不是因为能省很多。
     * <p>
     * <b>⚠️ 曾经以为它还能「修复缓存前缀」—— 那个推断是错的，实测没发生，别再照着重写。</b>
     * 原推断是「本轮尾部 {@code qN, qN}、下一轮同位置 {@code qN, aN}，所以重复项打断了前缀」。
     * 错在哪：重复项永远在<b>最末尾</b>，而下一轮在同一位置必然换成上一轮的回复 ——
     * 它从来就不属于「两轮共享的前缀」，删掉它并不会让可命中的前缀变长。
     * <p>
     * 实测（同一批 8 轮记录，见 {@code scripts/check-cache-claim.py}）：
     * <ul>
     *   <li>{@code cacheHit} 恒为 128 的整数倍（768/896/1024/1152/1280），缓存按 128 量化；</li>
     *   <li>命中量基本就等于 <b>system + 上下文</b>（压缩后 1541 字 ≈ 896 token），
     *       <b>历史部分无论改前改后都没进缓存</b>；</li>
     *   <li>「hit = floor(上一轮 prompt / 128) × 128」这个预言在 8 轮里只对了 2 轮 → 推断不成立。</li>
     * </ul>
     * 历史之所以进不了缓存，可能是两个原因（本数据不足以区分，但都与本次去重无关）：
     * 一是 {@link ConversationMemory#MAX_RECENT_MESSAGES} 那个滑窗每轮移动 2 条，
     * 前缀结构上就不稳定；二是每轮间隔只有 1~2 秒，而 DeepSeek 的缓存要数秒才建好，尾部来不及落缓存。
     * <p>
     * 信息量不变：删掉的这一条，正是紧随其后要追加的那份；此前真实发生过的对话
     * 一条都没少（滑窗上限内仍是同样多的「非重复」消息）。
     * <p>
     * 只删「最后一条 && role=user && 内容与 question 逐字相同」的那一条，
     * 所以玩家故意重复问同一句话时，历史里更早的那次仍会被保留。
     */
    static List<ChatMessage> stripCurrentQuestion(List<ChatMessage> messages, String question) {
        if (messages == null || messages.isEmpty() || question == null) {
            return messages == null ? List.of() : messages;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if ("user".equals(last.getRole()) && question.equals(last.getContent())) {
            return List.copyOf(messages.subList(0, messages.size() - 1));
        }
        return messages;
    }
}
