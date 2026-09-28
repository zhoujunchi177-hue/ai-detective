package com.mindtrace.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.entity.ChatMessage;
import com.mindtrace.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ConversationMemory {
    /**
     * 喂给模型的上下文条数。必须截断，否则 token 会随对话轮次线性增长。
     * <p>
     * ⚠️ <b>这个截断是有代价的，2026-09-21 实测记录在此，改这个值时请一并权衡。</b>
     * <pre>
     *   窗口未满（历史 1~11 条）  cacheMiss ≈ 140~250 token/轮
     *   窗口已满（历史 =12 条）   cacheMiss ≈ 390~485 token/轮
     * </pre>
     * 「窗口满后 miss 上升」这个现象<b>稳定复现</b>（4 次独立运行，含一次每轮间隔 12 秒的
     * 「模拟真人节奏」对照）。但它<b>不等于「每轮白多付 200 token、改一下就能省掉」</b>，
     * 原因见下 —— 别把它当成一个现成的优化项去动。
     * <p>
     * <b>⚠️ 我原先写在这里的解释（「滑窗让前缀每轮断一次」）已被实测推翻，别照着改。</b>
     * 检验方法：若前缀真的延续，第 N+1 轮的 {@code cacheHit} 应 ≈
     * {@code floor(第 N 轮 prompt / 128) × 128}（128 是因为实测 cacheHit 恒为 128 的整数倍）。
     * 结果 <b>4 次运行都只有 2/7 轮成立</b>。
     * <p>
     * 实测还量到两件事：
     * <ul>
     *   <li>{@code cacheHit} 基本只覆盖 <b>system + 注入上下文</b>
     *       （压缩后 1541 字 ≈ 896~1024 token），<b>历史部分几乎从不进缓存</b>；</li>
     *   <li>把轮间间隔从约 1.7 秒拉到 12 秒，命中量<b>没有任何变化</b> ——
     *       所以「缓存还没建好就发了下一条」也解释不了它。</li>
     * </ul>
     * 结论：miss 会随历史增长而上升，但其中<b>可归因于滑窗的只有约一个 128 量化档（≈128 token）</b>，
     * 其余只是「输入本来就变长了」。上游缓存的命中边界行为目前<b>没有可靠模型</b>，
     * <b>因此不要基于「改截断策略能省很多」去做改动</b> —— 真要动，先用
     * {@code scripts/check-cache-claim.py} 量一遍再说。
     * <p>
     * 不截断（让上下文单调增长）能让输入保持完整，<b>但 token 数量会持续上升</b> ——
     * 与「减少 token 消耗」的目标相反。所以这是个取舍点，不要只朝一个方向调。
     */
    private static final int MAX_RECENT_MESSAGES = 12;
    /**
     * 界面能回看的历史条数。
     * <p>
     * 和 {@link #MAX_RECENT_MESSAGES} 是**两个不同的需求，绝不能共用一个值**：
     * 上下文要截断（模型不需要整段历史），但**玩家需要** —— 截断会让
     * 「我前面明明问过的问题」在刷新后凭空消失，而数据其实一直在库里。
     */
    private static final int MAX_HISTORY_MESSAGES = 200;
    private final ChatMessageMapper chatMessageMapper;

    public List<ChatMessage> recent(Long userId, Long caseId, Long npcId) {
        return load(userId, caseId, npcId, MAX_RECENT_MESSAGES);
    }

    /**
     * 给界面回看的对话历史。
     * <p>
     * 为什么要单独一个方法：2026-09-21 实测，问满 10 轮再刷新页面，
     * **前 4 轮整段不见了** —— 因为界面接口复用了 {@link #recent}，被 12 条上限截掉。
     * 界面上看起来像「记录丢了」，实际数据完好，这种「假 bug」最劝退玩家：
     * 他没法回头引用自己问过的内容，而「记得前面聊过什么」正是这个游戏的对话要求之一。
     */
    public List<ChatMessage> history(Long userId, Long caseId, Long npcId) {
        return load(userId, caseId, npcId, MAX_HISTORY_MESSAGES);
    }

    /** 取最近 limit 条并按时间正序返回（SQL 里倒序取，再反转）。 */
    private List<ChatMessage> load(Long userId, Long caseId, Long npcId, int limit) {
        List<ChatMessage> messages = chatMessageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getUserId, userId)
                .eq(ChatMessage::getCaseId, caseId)
                .eq(ChatMessage::getNpcId, npcId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + limit));
        List<ChatMessage> result = new ArrayList<>(messages);
        Collections.reverse(result);
        return result;
    }

    public ChatMessage save(Long userId, Long caseId, Long npcId, String role, String content) {
        return insert(userId, caseId, npcId, role, content);
    }

    /**
     * 立刻落库，且**不并入调用方的事务**。
     * <p>
     * 对话流程是「存用户消息 → 解锁线索 → 调 AI → 存回复」。真实 AI 调用要几秒到几十秒，
     * 若用户消息跟着调用方的事务一起提交，那么在这段时间里它对任何其他连接都不可见 ——
     * 用户一刷新页面，自己刚发的问题就没了（而界面上明明显示过）。
     * 所以用户消息走独立事务，发送即持久化。
     * <p>
     * 注意：这个方法必须由**其他 bean** 调用，Spring 的事务代理才会生效；
     * 在本类内部自调用（如 save 调它）会绕过代理，REQUIRES_NEW 形同虚设。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatMessage saveImmediately(Long userId, Long caseId, Long npcId, String role, String content) {
        return insert(userId, caseId, npcId, role, content);
    }

    private ChatMessage insert(Long userId, Long caseId, Long npcId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(userId);
        message.setCaseId(caseId);
        message.setNpcId(npcId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(java.time.LocalDateTime.now());
        chatMessageMapper.insert(message);
        return message;
    }
}

