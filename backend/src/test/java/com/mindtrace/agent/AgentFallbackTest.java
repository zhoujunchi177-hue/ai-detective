package com.mindtrace.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.Npc;
import com.mindtrace.entity.NpcKnowledge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 不可用时的本地降级测试。
 * 这是本项目最关键的安全边界之一：NPC 只能回答其知识边界内的内容，不知道就必须说不知道，
 * 绝不能为了“像 AI”而编造事实。
 * <p>
 * 同时钉住「语气」这条产品要求：降级回复也必须像角色在说话 —— 短、口语、不声明自己的信息范围、
 * 不重复自我介绍。2026-09-21 之前这里是「就我所知……这是我愿意且能够说明的部分」那种报告腔，
 * 与真实 AI 的人格设定割裂，因此连同提示词一起改掉了。
 */
class AgentFallbackTest {

    // 降级分支不触碰任何注入依赖，因此可以直接传 null 构造。
    private final AgentService agentService = new AgentService(null, null, null, null);

    /**
     * 「我不知道」的表达方式。允许若干口语变体（不知道 / 没见过 / 确定不了 / 答不上来 …），
     * 但必须是明确的「不知道」，不能是编出来的内容。
     */
    private static final Pattern UNKNOWN_MARKER =
            Pattern.compile("不知道|不清楚|不记得|没见过|确定不了|答不上来|不了解");

    /** 降级回复不该比「一句开场 + 知识原文」更长。 */
    private static final int LEAD_IN_MAX = 12;

    private AgentContext context(String question, List<NpcKnowledge> knowledge, List<Clue> discoveredClues) {
        Npc npc = new Npc();
        npc.setName("米娅·托雷斯");
        npc.setGreeting("我只能根据值班记录和公开资料回答。");
        return new AgentContext(
                null, null, npc, question,
                List.of(), discoveredClues, List.of("CONTRADICTION"),
                knowledge, List.of(), List.of());
    }

    private NpcKnowledge knowledge(String topic, String content) {
        NpcKnowledge item = new NpcKnowledge();
        item.setTopic(topic);
        item.setContent(content);
        item.setDisclosureLevel("normal");
        return item;
    }

    private List<Clue> clues(int count) {
        List<Clue> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Clue clue = new Clue();
            clue.setId((long) (i + 1));
            clue.setType("TIMELINE");
            result.add(clue);
        }
        return result;
    }

    private void assertSaysUnknown(String reply) {
        assertTrue(UNKNOWN_MARKER.matcher(reply).find(),
                "降级回复应明确表示不知道，实际是：" + reply);
    }

    @Test
    @DisplayName("命中知识边界时给出该 NPC 掌握的内容，并标记为降级结果")
    void answersWithinKnowledgeBoundary() {
        List<NpcKnowledge> knowledge = List.of(knowledge("时间线", "凌晨一时前后走廊记录不完整"));

        AgentAnswer answer = agentService.fallbackNpc(
                context("请说说时间线", knowledge, List.of()), "尚未配置 DeepSeek API Key");

        assertTrue(answer.content().contains("凌晨一时前后走廊记录不完整"));
        assertFalse(answer.aiAvailable());
        assertEquals("尚未配置 DeepSeek API Key", answer.notice());
    }

    @Test
    @DisplayName("问题超出知识边界时明确表示不知道，不编造")
    void refusesTopicOutsideKnowledgeBoundary() {
        List<NpcKnowledge> knowledge = List.of(knowledge("时间线", "凌晨一时前后走廊记录不完整"));

        AgentAnswer answer = agentService.fallbackNpc(
                context("凶手到底是谁", knowledge, List.of()), "尚未配置 DeepSeek API Key");

        assertSaysUnknown(answer.content());
        assertFalse(answer.content().contains("凌晨一时前后走廊记录不完整"));
    }

    @Test
    @DisplayName("完全没有知识记录时也不编造具体事实")
    void refusesWhenNoKnowledgeAtAll() {
        AgentAnswer answer = agentService.fallbackNpc(
                context("凶手到底是谁", List.of(), List.of()), "尚未配置 DeepSeek API Key");

        assertSaysUnknown(answer.content());
        assertFalse(answer.aiAvailable());
    }

    @Test
    @DisplayName("降级回复保持口语化短句，不写报告、不重复自我介绍")
    void fallbackRepliesStayShortAndInCharacter() {
        List<NpcKnowledge> knowledge = List.of(knowledge("时间线", "凌晨一时前后走廊记录不完整"));

        AgentAnswer matched = agentService.fallbackNpc(
                context("请说说时间线", knowledge, List.of()), "notice");
        AgentAnswer unknown = agentService.fallbackNpc(
                context("凶手到底是谁", knowledge, List.of()), "notice");

        // 命中时只允许比知识原文多一句很短的开场，不能额外堆免责声明。
        assertTrue(matched.content().length() <= "凌晨一时前后走廊记录不完整".length() + LEAD_IN_MAX,
                "命中知识时不该附加长篇说明，实际是：" + matched.content());
        // 未命中时就是一句话，不该把开场白再念一遍。
        assertTrue(unknown.content().length() <= 40,
                "未命中时应是一句口语短句，实际是：" + unknown.content());
        assertFalse(matched.content().contains("我只能根据值班记录和公开资料回答。"),
                "降级回复不该重复 NPC 的开场白");
        assertFalse(unknown.content().contains("我只能根据值班记录和公开资料回答。"),
                "降级回复不该重复 NPC 的开场白");
    }

    @Test
    @DisplayName("本地推理返回可解析的 JSON，且字段齐全")
    void localReasoningReturnsParsableJson() throws Exception {
        AgentAnswer answer = agentService.localReasoning(
                context("我认为时间码不可靠", List.of(), clues(13)), "尚未配置 DeepSeek API Key");

        JsonNode json = new ObjectMapper().readTree(answer.content());
        assertTrue(json.path("summary").isTextual());
        assertTrue(json.path("supportingEvidence").isArray());
        assertTrue(json.path("contradictions").isArray());
        assertTrue(json.path("missingEvidence").isArray());
        assertTrue(json.path("suggestions").isArray());
        assertTrue(json.path("confidence").isInt());
        assertFalse(answer.aiAvailable());
    }

    @Test
    @DisplayName("本地推理 confidence 随线索增加，但不超过 72 的上限")
    void localReasoningConfidenceIsCapped() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode withoutClues = mapper.readTree(agentService
                .localReasoning(context("假设", List.of(), List.of()), "notice").content());
        JsonNode withManyClues = mapper.readTree(agentService
                .localReasoning(context("假设", List.of(), clues(20)), "notice").content());

        assertEquals(20, withoutClues.path("confidence").asInt());
        assertEquals(72, withManyClues.path("confidence").asInt());
    }

    @Test
    @DisplayName("玩家问题为空（null）时不崩，按「不知道」处理")
    void nullQuestionFallsBackToUnknown() {
        // 降级路径要能承受残缺输入：question 为 null 时不能 NPE，
        // 也不能因为「空问题」而误命中某条知识。
        AgentAnswer answer = agentService.fallbackNpc(
                context(null, List.of(knowledge("时间线", "凌晨一时前后走廊记录不完整")), List.of()),
                "尚未配置 DeepSeek API Key");

        assertSaysUnknown(answer.content());
        assertFalse(answer.aiAvailable());
    }

    @Test
    @DisplayName("问题只命中知识条目的正文（未命中主题）时也算命中")
    void matchesWhenOnlyContentMatches() {
        // 主题是「时间线」，但玩家问的是「走廊」—— 只有正文包含它。
        // 若匹配只看主题，这条知识会被漏掉，NPC 会白说「不知道」。
        List<NpcKnowledge> knowledge = List.of(knowledge("时间线", "凌晨一时前后走廊记录不完整"));

        AgentAnswer answer = agentService.fallbackNpc(
                context("走廊", knowledge, List.of()), "尚未配置 DeepSeek API Key");

        assertTrue(answer.content().contains("凌晨一时前后走廊记录不完整"));
    }

    @Test
    @DisplayName("单字提问不会被拿来匹配，避免误命中")
    void singleCharacterTokensAreIgnored() {
        // 「走」这类单字太泛，参与匹配会把无关知识也命中。
        List<NpcKnowledge> knowledge = List.of(knowledge("时间线", "凌晨一时前后走廊记录不完整"));

        AgentAnswer answer = agentService.fallbackNpc(
                context("走", knowledge, List.of()), "尚未配置 DeepSeek API Key");

        assertSaysUnknown(answer.content());
    }

    @Test
    @DisplayName("知识条目的主题为空或缺失时被跳过，不抛异常")
    void blankOrNullKnowledgeFieldIsTolerated() {
        // 内容维护时可能漏填 topic。空主题不能被拿去匹配，也不能让整条知识失效。
        AgentAnswer nullTopic = agentService.fallbackNpc(
                context("走廊", List.of(knowledge(null, "凌晨一时前后走廊记录不完整")), List.of()),
                "notice");
        assertTrue(nullTopic.content().contains("凌晨一时前后走廊记录不完整"));

        AgentAnswer blankTopic = agentService.fallbackNpc(
                context("走廊", List.of(knowledge("   ", "凌晨一时前后走廊记录不完整")), List.of()),
                "notice");
        assertTrue(blankTopic.content().contains("凌晨一时前后走廊记录不完整"));
    }
}
