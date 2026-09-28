package com.mindtrace.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindtrace.config.DeepSeekProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 响应解析与配置检测测试。
 * 这里只覆盖不依赖网络的分支：JSON 解析与 API Key 配置判断。
 * 真实的 HTTP 调用需要配置 DEEPSEEK_API_KEY 后另行验证。
 */
class DeepSeekServiceTest {

    private DeepSeekService service(DeepSeekProperties properties) {
        // parseJson / isConfigured 不使用 RestClient，因此第一个参数传 null 是安全的。
        return new DeepSeekService(null, properties, new ObjectMapper());
    }

    @Test
    @DisplayName("能解析普通 JSON")
    void parsesPlainJson() throws Exception {
        JsonNode node = service(new DeepSeekProperties()).parseJson("{\"summary\":\"ok\",\"confidence\":80}");
        assertEquals("ok", node.path("summary").asText());
        assertEquals(80, node.path("confidence").asInt());
    }

    @Test
    @DisplayName("能剥离 ```json 代码块包裹")
    void parsesFencedJsonBlock() throws Exception {
        String fenced = "```json\n{\"summary\":\"ok\",\"confidence\":55}\n```";
        JsonNode node = service(new DeepSeekProperties()).parseJson(fenced);
        assertEquals(55, node.path("confidence").asInt());
    }

    @Test
    @DisplayName("非 JSON 内容会抛异常，交由上层走降级而不是直接透传")
    void rejectsNonJsonContent() {
        DeepSeekService service = service(new DeepSeekProperties());
        assertThrows(Exception.class, () -> service.parseJson("模型今天不听话，返回了这段文字"));
    }

    @Test
    @DisplayName("未设置或空白 API Key 时判定为未配置，服务不应崩溃")
    void notConfiguredWithoutApiKey() {
        DeepSeekProperties properties = new DeepSeekProperties();
        assertFalse(service(properties).isConfigured());

        properties.setApiKey("   ");
        assertFalse(service(properties).isConfigured());

        properties.setApiKey("sk-test-placeholder");
        assertTrue(service(properties).isConfigured());
    }
}
