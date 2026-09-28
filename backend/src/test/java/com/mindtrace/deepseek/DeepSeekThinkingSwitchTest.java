package com.mindtrace.deepseek;

import com.mindtrace.config.DeepSeekProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 「关闭思考」开关的请求体测试。
 *
 * <p>为什么要有这一组测试：思考 token 占 NPC completion 的 55.7%、占 JSON 链路约 84%，
 * 关掉它能省约 90% 输出 token —— 但**上游只认 {@code thinking:{"type":"disabled"}}**。
 * 网上（以及若干第三方文档）广泛流传的 {@code enable_thinking:false}
 * 实测**会被接受但完全无效**（见 {@code scripts/probe-thinking-param.py}）。
 * 所以这里专门钉住「用哪个字段」和「默认不发」，避免后人照抄文档把开关改坏、
 * 或者让默认行为悄悄变化。
 *
 * <p>无 Mockito：{@code buildPayload} 是 static 纯函数，直接构造 properties 即可。
 */
class DeepSeekThinkingSwitchTest {

    private static DeepSeekProperties props(boolean disableThinking) {
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setModel("deepseek-flash");
        properties.setDisableThinking(disableThinking);
        return properties;
    }

    private static Map<String, Object> payload(boolean jsonMode, boolean disableThinking) {
        return DeepSeekService.buildPayload(
                List.of(new DeepSeekMessage("system", "s"), new DeepSeekMessage("user", "u")),
                jsonMode, props(disableThinking));
    }

    @Test
    @DisplayName("默认不发 thinking 字段，保证升级不会悄悄改变模型行为")
    void omitsThinkingSwitchByDefault() {
        assertFalse(payload(false, false).containsKey("thinking"),
                "默认（disableThinking=false）不应出现 thinking 字段");
        assertFalse(payload(true, false).containsKey("thinking"),
                "JSON 模式默认同样不应出现 thinking 字段");
    }

    @Test
    @DisplayName("开启后发送 thinking:{type:disabled} —— 上游唯一认的写法")
    void sendsThinkingDisabledWhenSwitchedOn() {
        assertEquals(Map.of("type", "disabled"), payload(false, true).get("thinking"));
        assertEquals(Map.of("type", "disabled"), payload(true, true).get("thinking"));
    }

    @Test
    @DisplayName("绝不能发 enable_thinking：上游会接受但完全忽略，是个陷阱")
    void neverSendsEnableThinking() {
        for (boolean disableThinking : new boolean[]{false, true}) {
            for (boolean jsonMode : new boolean[]{false, true}) {
                assertFalse(payload(jsonMode, disableThinking).containsKey("enable_thinking"),
                        "enable_thinking 实测无效（probe-thinking-param.py），不要用它");
            }
        }
    }

    @Test
    @DisplayName("开关不影响其余字段：模型名、消息顺序、温度、输出上限、response_format")
    void keepsOtherFieldsIntact() {
        Map<String, Object> chat = payload(false, true);
        assertEquals("deepseek-flash", chat.get("model"));
        assertEquals(0.55, ((Number) chat.get("temperature")).doubleValue());
        assertEquals(1200, chat.get("max_tokens"));
        assertFalse(chat.containsKey("response_format"), "对话模式不应带 response_format");

        Map<String, Object> json = payload(true, true);
        assertEquals(0.2, ((Number) json.get("temperature")).doubleValue());
        assertEquals(8000, json.get("max_tokens"));
        assertEquals(Map.of("type", "json_object"), json.get("response_format"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) json.get("messages");
        assertEquals(List.of("system", "user"),
                messages.stream().map(item -> item.get("role")).toList());
    }
}
