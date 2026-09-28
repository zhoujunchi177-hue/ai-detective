package com.mindtrace.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindtrace.deepseek.DeepSeekMessage;
import com.mindtrace.deepseek.DeepSeekService;
import com.mindtrace.exception.AiUnavailableException;
import com.mindtrace.mapper.NpcKnowledgeMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mindtrace.entity.NpcKnowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {
    private final DeepSeekService deepSeekService;
    private final ContextBuilder contextBuilder;
    private final PromptBuilder promptBuilder;
    private final NpcKnowledgeMapper npcKnowledgeMapper;

    /** 命中知识边界时的口语化开场。 */
    private static final String[] REMEMBER_LEADS = {
            "嗯，这个我记得。", "你问到点子上了。", "这个我有印象。", "哦，这个啊。", "这个我倒是记得。"
    };

    /** 没命中知识边界时的「不知道」。全部是口语，不是「我没有接触过这部分信息」那种书面话。 */
    private static final String[] UNKNOWN_REPLIES = {
            "这个我真不知道。", "这个我没见过记录。", "这个我确定不了。",
            "这个我不清楚，你问问别人？", "这我可答不上来。"
    };

    /** NPC 回复的软上限（字）。超过就记 warn，见 {@link #warnIfReportTone}。 */
    private static final int NPC_REPLY_SOFT_LIMIT = 200;

    /** 玩家明确要求详细说明时，不受软上限约束。 */
    private static final Pattern DETAIL_REQUEST =
            Pattern.compile("详细|展开|完整|全部|逐一|一条条|列一下|都说|多讲|慢慢说");

    public AgentAnswer npcReply(Long userId, Long caseId, Long npcId, String question) {
        AgentContext context = contextBuilder.buildNpcContext(userId, caseId, npcId, question);
        if (!deepSeekService.isConfigured()) {
            return fallbackNpc(context, "尚未配置 DeepSeek API Key，请设置 DEEPSEEK_API_KEY。");
        }
        try {
            String answer = deepSeekService.chat(promptBuilder.npcDialoguePrompt(context), false);
            // 提示词要求全角标点，但那是概率性的（128 轮里仍漏 2 轮）。
            // 排版是确定性缺陷，用代码兜底一次，不指望模型每轮都听话。
            answer = TextStyle.toFullWidthPunctuation(answer);
            warnIfReportTone(question, answer);
            return new AgentAnswer(answer, true, null);
        } catch (AiUnavailableException exception) {
            // 降级本身是设计好的（HTTP 200 + aiAvailable=false），但**必须留下日志**：
            // 否则「这一轮为什么只有 13 个字」在日志里完全查不到，只能靠猜上游是 429 还是超时。
            log.warn("NPC 对话降级为本地回复：{}", exception.getMessage());
            return fallbackNpc(context, exception.getMessage());
        }
    }

    public AgentAnswer reasoning(Long userId, Long caseId, String hypothesis) {
        AgentContext context = contextBuilder.buildReasoningContext(userId, caseId, hypothesis);
        if (!deepSeekService.isConfigured()) {
            return localReasoning(context, "尚未配置 DeepSeek API Key，请设置 DEEPSEEK_API_KEY。");
        }
        try {
            String content = deepSeekService.chat(promptBuilder.reasoningPrompt(context), true);
            return new AgentAnswer(content, true, null);
        } catch (AiUnavailableException exception) {
            return localReasoning(context, exception.getMessage());
        }
    }

    public AgentAnswer finalAnalysis(Long userId, Long caseId, String submission) {
        AgentContext context = contextBuilder.buildReasoningContext(userId, caseId, submission);
        if (!deepSeekService.isConfigured()) {
            return new AgentAnswer("""
                    {"summary":"调查报告已根据游戏内线索生成；当前未配置 AI，因此此处使用基础评价。",
                    "strengths":["已完成结案提交并保留结构化推理记录"],
                    "keyEvidence":[],"missedClues":[],"timelineIssues":[],"logicGaps":[],
                    "nextSteps":["继续调查地点、询问 NPC，并完成剩余谜题"],
                    "realityNotice":"【现实案件资料】该案件在现实中可能仍未结案或存在争议，游戏推理不代表现实结论。"}
                    """, false, "尚未配置 DeepSeek API Key，请设置 DEEPSEEK_API_KEY。");
        }
        try {
            String content = deepSeekService.chat(promptBuilder.finalAnalysisPrompt(context, submission), true);
            return new AgentAnswer(content, true, null);
        } catch (AiUnavailableException exception) {
            return new AgentAnswer("""
                    {"summary":"DeepSeek 暂时不可用，Java 已完成游戏评分并保留全部进度。",
                    "strengths":["结案记录已保存"],"keyEvidence":[],"missedClues":[],
                    "timelineIssues":[],"logicGaps":[],
                    "nextSteps":["稍后可重新查看本案调查档案"],
                    "realityNotice":"【现实案件资料】游戏推理不代表现实案件结论。"}
                    """, false, exception.getMessage());
        }
    }

    public JsonNode parseOrNull(String content) {
        try {
            return deepSeekService.parseJson(content);
        } catch (Exception ignored) {
            return null;
        }
    }

    // 未配置 AI 或 AI 不可用时的本地降级：只用当前 NPC 的知识边界回答，绝不编造。
    // 语气必须和真实 AI 一致 —— 这里是一个角色在说话，不是一份调查报告：
    // 短句、口语、不声明「我掌握的信息范围」，不知道就直说不知道。
    AgentAnswer fallbackNpc(AgentContext context, String notice) {
        String question = context.playerQuestion() == null ? "" : context.playerQuestion();
        var knowledge = context.npcKnowledge();
        NpcKnowledge matched = knowledge.stream()
                .filter(item -> containsAny(question, item.getTopic()) || containsAny(question, item.getContent()))
                .findFirst()
                .orElse(null);
        String reply = matched != null ? remember(matched.getContent()) : doNotKnow(question);
        // 降级文案是从 npc_knowledge 拼出来的，内容里万一半角标点也一并归一化，
        // 免得「真实调用是全角、降级是半角」这种不一致暴露给玩家。
        return new AgentAnswer(TextStyle.toFullWidthPunctuation(reply), false, notice);
    }

    /** 命中知识边界时的口语化开场。按内容散列挑选，保证同一问题每次回答一致。 */
    private String remember(String content) {
        return REMEMBER_LEADS[Math.floorMod(content.hashCode(), REMEMBER_LEADS.length)] + content;
    }

    /** 没命中时的「不知道」。同样按问题散列挑选，避免每轮都是同一句。 */
    private String doNotKnow(String question) {
        return UNKNOWN_REPLIES[Math.floorMod(question.hashCode(), UNKNOWN_REPLIES.length)];
    }

    /**
     * NPC 回复长度的观测点。
     * <p>
     * 玩家要的是「问一个人」，不是「读一份调查报告」，所以正常回复应当只有 2~6 句。
     * 这里**不裁剪内容**（裁掉尾巴可能正好删掉一条线索），只在上限被突破时记 warn，
     * 用来发现「提示词被忽略、模型又开始写报告」的回归。
     */
    private void warnIfReportTone(String question, String reply) {
        if (reply == null) {
            return;
        }
        String compact = reply.replaceAll("\\s", "");
        if (compact.length() <= NPC_REPLY_SOFT_LIMIT
                || DETAIL_REQUEST.matcher(question == null ? "" : question).find()) {
            return;
        }
        log.warn("NPC 回复 {} 字，超过软上限 {} 字，可能又写成了报告（玩家问题：{}）。",
                compact.length(), NPC_REPLY_SOFT_LIMIT, question);
    }

    // 未配置 AI 时的本地推理降级：只基于玩家已获得线索数量给出保守结论。
    AgentAnswer localReasoning(AgentContext context, String notice) {
        int clueCount = context.discoveredClues().size();
        int confidence = Math.min(72, 20 + clueCount * 5);
        String summary = clueCount == 0
                ? "当前尚未掌握可验证线索，暂时只能视为假设。"
                : "你的假设与当前 " + clueCount + " 条已获得线索进行了对照，部分内容可继续验证。";
        String json = """
                {
                  "summary": %s,
                  "supportingEvidence": ["使用了当前已获得的游戏线索进行对照"],
                  "contradictions": [],
                  "missingEvidence": %s,
                  "suggestions": ["继续调查地点并与 NPC 交叉验证时间线"],
                  "confidence": %d
                }
                """.formatted(jsonString(summary), jsonString(context.missingClueTopics()), confidence);
        return new AgentAnswer(json, false, notice);
    }

    private boolean containsAny(String question, String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        return Arrays.stream(question.split("[，。！？\\s]+"))
                .filter(token -> token.length() >= 2)
                .anyMatch(token -> source.contains(token) || token.contains(source.substring(0, Math.min(2, source.length()))));
    }

    private String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String jsonString(java.util.List<String> values) {
        return values.stream().map(this::jsonString).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
