package com.mindtrace.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.agent.AgentAnswer;
import com.mindtrace.agent.AgentService;
import com.mindtrace.agent.ConversationMemory;
import com.mindtrace.dto.AgentDtos;
import com.mindtrace.dto.CaseDtos;
import com.mindtrace.entity.Npc;
import com.mindtrace.exception.BusinessException;
import com.mindtrace.mapper.NpcMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final NpcMapper npcMapper;
    private final CaseQueryService caseQueryService;
    private final ClueUnlockService clueUnlockService;
    private final AgentService agentService;
    private final ConversationMemory conversationMemory;

    /**
     * 界面回看用的对话历史。
     * <p>
     * ⚠️ 这里必须用 {@code conversationMemory.history()}，**不能**用 {@code recent()} ——
     * 后者带 12 条上限（那是给模型上下文用的），拿它当界面接口会让玩家
     * 「问满 10 轮、一刷新前 4 轮就没了」。两个需求不同，见 {@link ConversationMemory}。
     */
    public List<CaseDtos.ChatView> history(Long caseId, Long userId, Long npcId) {
        caseQueryService.requireCase(caseId);
        Npc npc = npcMapper.selectById(npcId);
        if (npc == null || !caseId.equals(npc.getCaseId())) {
            throw new BusinessException(404, "NPC 不存在");
        }
        return conversationMemory.history(userId, caseId, npcId).stream()
                .map(message -> new CaseDtos.ChatView(message.getId(), message.getRole(),
                        message.getContent(), message.getCreatedAt()))
                .toList();
    }

    /**
     * 刻意**不加** {@code @Transactional}。
     * <p>
     * AI 调用要几秒到几十秒，一旦把它圈进事务，数据库连接就会被一直占用
     * （Hikari 默认池只有 10 个连接，十来个并发对话就能把池打满）。
     * 这里每个写操作本来就是独立的，不需要原子性：
     * <ul>
     *   <li>用户消息 → {@code saveImmediately}（{@code REQUIRES_NEW}，独立事务）</li>
     *   <li>线索解锁 → {@code ClueUnlockService.unlockByNpcKeyword}（自带 {@code @Transactional}）</li>
     *   <li>AI 回复 → 单条 insert，无事务时自动提交</li>
     * </ul>
     * 「用户说了这句话」和「这句话解锁了某条线索」是两件事，任一步失败都不该回滚另一步。
     */
    public AgentDtos.ChatResponse chat(Long caseId, Long userId, AgentDtos.ChatRequest request) {
        caseQueryService.requireCase(caseId);
        Npc npc = npcMapper.selectOne(Wrappers.<Npc>lambdaQuery()
                .eq(Npc::getId, request.npcId())
                .eq(Npc::getCaseId, caseId)
                .last("LIMIT 1"));
        if (npc == null) {
            throw new BusinessException(404, "NPC 不存在");
        }

        // 用户消息**先**落库，而且走独立事务立刻提交。
        // 原先它在 AI 调用之后、与回复同处一个事务：真实 AI 要几十秒，
        // 这段时间里消息对任何其他连接都不可见，用户一刷新就丢了自己刚发的问题。
        conversationMemory.saveImmediately(userId, caseId, npc.getId(), "user", request.message());

        var unlocked = clueUnlockService.unlockByNpcKeyword(
                userId, caseId, npc.getId(), request.message());
        AgentAnswer answer = agentService.npcReply(userId, caseId, npc.getId(), request.message());
        conversationMemory.save(userId, caseId, npc.getId(), "assistant", answer.content());

        return new AgentDtos.ChatResponse(
                answer.content(),
                answer.aiAvailable(),
                answer.notice(),
                unlocked.stream().map(item -> item.getId()).toList());
    }
}
