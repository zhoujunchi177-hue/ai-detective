package com.mindtrace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 60;
    /**
     * 普通对话（NPC 对话）的输出上限。
     */
    private int chatMaxTokens = 1200;
    /**
     * JSON 模式（推理分析、结案报告）的输出上限。
     * 注意：DeepSeek 的推理模型会把思维链 token 也计入 max_tokens，
     * 上限过小会导致 JSON 在数组中间被截断、解析失败并降级，因此这里给足空间。
     */
    private int jsonMaxTokens = 8000;
    /**
     * 是否关闭模型的「思考」过程。默认 false，即保持原有行为。
     *
     * <p>实测（<code>scripts/probe-thinking-param.py</code>，2026-09-21，model=deepseek-flash）：
     * <ul>
     *   <li>本模型默认<b>在思考</b>，思考 token 占 completion 的 55.7%（NPC 对话）～84%（JSON）；</li>
     *   <li>上游<b>只认</b> <code>thinking: {"type":"disabled"}</code>；网上广泛流传的
     *       <code>enable_thinking: false</code> 会被接受但<b>完全无效</b>（实测思考照旧），别照抄；</li>
     *   <li>关闭后 completion 从 210→12（对话）、463→41（JSON），约 −90%，
     *       且 JSON 模式仍返回合法 JSON、可见正文长度基本不变。</li>
     * </ul>
     *
     * <p>⚠️ 这是<b>行为变更</b>：思考有助于遵守 44 条约束与抵御提示词注入，因此默认不开启。
     * 开启（<code>DEEPSEEK_DISABLE_THINKING=true</code>）前，请先跑
     * <code>verify-npc-voice.py</code> ×7 与 <code>npc-chat-ui-check.mjs</code> 确认行为未退化。
     */
    private boolean disableThinking = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getChatMaxTokens() {
        return chatMaxTokens;
    }

    public void setChatMaxTokens(int chatMaxTokens) {
        this.chatMaxTokens = chatMaxTokens;
    }

    public int getJsonMaxTokens() {
        return jsonMaxTokens;
    }

    public void setJsonMaxTokens(int jsonMaxTokens) {
        this.jsonMaxTokens = jsonMaxTokens;
    }

    public boolean isDisableThinking() {
        return disableThinking;
    }

    public void setDisableThinking(boolean disableThinking) {
        this.disableThinking = disableThinking;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}

