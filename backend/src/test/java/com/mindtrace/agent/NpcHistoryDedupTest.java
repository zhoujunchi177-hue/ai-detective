package com.mindtrace.agent;

import com.mindtrace.deepseek.DeepSeekMessage;
import com.mindtrace.entity.CaseFile;
import com.mindtrace.entity.ChatMessage;
import com.mindtrace.entity.Npc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本轮提问被发给模型两次」的回归测试。
 * <p>
 * 缺陷成因：{@code ChatService.chat()} 为了「玩家一刷新不丢问题」，在调用 AI <b>之前</b>
 * 就把提问落库了（{@code saveImmediately}，独立事务）。于是 {@code recent()} 返回的历史
 * 最后一条**就是本轮提问**，而 {@code PromptBuilder} 又会追加一次 {@code playerQuestion}
 * —— 同一句话被发了两次。
 * <p>
 * 收益只有「少发一份提问」，很小（2026-09-21 实测约 7~9 token/轮）。
 * <p>
 * ⚠️ 曾经以为它还能「修复缓存前缀」（本轮尾部 {@code qN, qN} 打断下一轮的前缀），
 * <b>那个推断已被实测推翻</b>：重复项永远在<b>最末尾</b>，而下一轮在同一位置必然换成
 * 上一轮的回复，它从来不落在「两轮共享的前缀」里，删掉它并不会让可命中的前缀变长。
 * 详见 {@link ContextBuilder#stripCurrentQuestion} 的注释与 {@code scripts/check-cache-claim.py}。
 * 所以本测试<b>只</b>锁「提问不被发两次」这一件事，不承担任何缓存承诺。
 * <p>
 * 覆盖范围说明：本测试锁的是<b>纯逻辑</b>（{@link ContextBuilder#stripCurrentQuestion}）
 * 和它到 {@link PromptBuilder} 的<b>端到端效果</b>。
 * 「{@code ContextBuilder.build()} 确实调用了这个函数」这一环由
 * {@code scripts/measure-npc-tokens.py} 从计量代理记录里读 {@code breakdown} 来验证
 * （需要数据库，不适合放进单元测试）。
 * <p>
 * 不用 Mock（本项目 Java 25 + byte-buddy 不兼容）。
 */
class NpcHistoryDedupTest {

    private static ChatMessage msg(String role, String content) {
        ChatMessage row = new ChatMessage();
        row.setRole(role);
        row.setContent(content);
        return row;
    }

    private static List<String> contents(List<ChatMessage> rows) {
        return rows.stream().map(ChatMessage::getContent).toList();
    }

    private static CaseFile caseFile() {
        CaseFile file = new CaseFile();
        file.setTitle("水箱回声");
        return file;
    }

    private static Npc npc() {
        Npc npc = new Npc();
        npc.setId(1L);
        npc.setCaseId(1L);
        npc.setName("米娅·托雷斯");
        npc.setIdentity("酒店前台");
        npc.setPersonality("谨慎、话不多");
        npc.setLocation("大堂");
        npc.setRelationship("目击者");
        return npc;
    }

    // ---------- 纯逻辑 ----------

    @Test
    @DisplayName("历史末尾就是本轮提问时，必须去掉那一条（提问不能被发两次）")
    void dropsTrailingDuplicateOfCurrentQuestion() {
        List<ChatMessage> history = List.of(
                msg("user", "电梯当时有什么异常吗？"),
                msg("assistant", "电梯一直停在八楼。"),
                msg("user", "那天晚上你在前台吗？"));

        List<ChatMessage> stripped =
                ContextBuilder.stripCurrentQuestion(history, "那天晚上你在前台吗？");

        assertEquals(List.of("电梯当时有什么异常吗？", "电梯一直停在八楼。"), contents(stripped),
                "末尾那条与本轮提问逐字相同，应被去掉；此前真实发生过的对话一条都不能少");
    }

    @Test
    @DisplayName("玩家故意重复问同一句话时，历史里更早的那次仍要保留")
    void keepsEarlierIdenticalQuestion() {
        List<ChatMessage> history = List.of(
                msg("user", "电梯当时有什么异常吗？"),
                msg("assistant", "电梯一直停在八楼。"),
                msg("user", "电梯当时有什么异常吗？"));

        List<ChatMessage> stripped =
                ContextBuilder.stripCurrentQuestion(history, "电梯当时有什么异常吗？");

        assertEquals(List.of("电梯当时有什么异常吗？", "电梯一直停在八楼。"), contents(stripped),
                "只该删末尾那一条；玩家确实问过两次，历史要如实反映");
    }

    @Test
    @DisplayName("末尾不是本轮提问（如上一轮 AI 调用失败）时，一条都不许删")
    void keepsHistoryWhenLastIsNotTheCurrentQuestion() {
        List<ChatMessage> history = List.of(
                msg("user", "电梯当时有什么异常吗？"),
                msg("assistant", "电梯一直停在八楼。"));

        List<ChatMessage> stripped =
                ContextBuilder.stripCurrentQuestion(history, "那天晚上你在前台吗？");

        assertSame(history, stripped, "无需改动时必须原样返回（同实例），不做无谓拷贝");
    }

    @Test
    @DisplayName("末尾是本轮提问但 role 不是 user 时，不删")
    void doesNotDropNonUserMessageWithSameContent() {
        List<ChatMessage> history = List.of(
                msg("user", "电梯当时有什么异常吗？"),
                msg("assistant", "那天晚上你在前台吗？"));

        List<ChatMessage> stripped =
                ContextBuilder.stripCurrentQuestion(history, "那天晚上你在前台吗？");

        assertSame(history, stripped, "内容碰巧相同但 role=assistant，属于 NPC 说过的话，不能当成重复提问删掉");
    }

    @Test
    @DisplayName("空历史 / null 提问都不炸，也不误删")
    void handlesEmptyHistoryAndNullQuestion() {
        assertTrue(ContextBuilder.stripCurrentQuestion(List.of(), "随便问点什么").isEmpty(),
                "空历史应该返回空");

        List<ChatMessage> history = List.of(msg("user", "电梯当时有什么异常吗？"));
        assertSame(history, ContextBuilder.stripCurrentQuestion(history, null),
                "提问为 null 时无从比对，应原样返回");
    }

    @Test
    @DisplayName("单轮对话：只有本轮提问时，历史被清空（而不是留下一条重复）")
    void firstTurnHistoryBecomesEmpty() {
        List<ChatMessage> history = List.of(msg("user", "电梯当时有什么异常吗？"));

        List<ChatMessage> stripped =
                ContextBuilder.stripCurrentQuestion(history, "电梯当时有什么异常吗？");

        assertTrue(stripped.isEmpty(), "第一轮的历史只有刚落库的提问本身，去掉后应为空");
    }

    // ---------- 端到端：走完整 PromptBuilder ----------

    @Test
    @DisplayName("端到端：组装完提示词后，本轮提问在整串消息里只出现一次，且仍在最后")
    void questionAppearsExactlyOnceInFullPrompt() {
        String question = "那天晚上你在前台吗？";
        AgentContext context = new AgentContext(
                null,
                caseFile(),
                npc(),
                question,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ContextBuilder.stripCurrentQuestion(List.of(
                        msg("user", "电梯当时有什么异常吗？"),
                        msg("assistant", "电梯一直停在八楼。"),
                        msg("user", question)), question));

        List<DeepSeekMessage> messages = new PromptBuilder().npcDialoguePrompt(context);

        long occurrences = messages.stream()
                .filter(message -> question.equals(message.content()))
                .count();
        assertEquals(1, occurrences,
                "提问在整串消息里出现了 " + occurrences + " 次。同一句话重复发两遍纯属冗余"
                        + "（每轮白付一份提问的 token），而且会让模型看到一句「被强调过」的话，"
                        + "与真实对话不符");

        assertEquals(question, messages.get(messages.size() - 1).content(),
                "提问仍必须是最后一条消息（贴着生成位置）");
        assertEquals("system", messages.get(0).role(), "第一条仍是 system");
        assertEquals("user", messages.get(1).role(), "第二条仍是上下文");
        assertEquals(List.of("system", "user", "user", "assistant", "user"),
                messages.stream().map(DeepSeekMessage::role).toList(),
                "消息序列应为 system → 上下文 → 历史(user/assistant 交替) → 提问（共 5 条）。"
                        + "若末尾出现两条连续的相同提问，说明去重没生效");
    }
}
