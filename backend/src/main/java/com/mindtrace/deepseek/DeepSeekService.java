package com.mindtrace.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindtrace.config.DeepSeekProperties;
import com.mindtrace.exception.AiUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {
    private final RestClient deepSeekRestClient;
    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public String chat(List<DeepSeekMessage> messages, boolean jsonMode) {
        if (!isConfigured()) {
            throw new AiUnavailableException("尚未配置 DeepSeek API Key，请设置 DEEPSEEK_API_KEY。");
        }

        Map<String, Object> payload = buildPayload(messages, jsonMode, properties);

        try {
            JsonNode response = deepSeekRestClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode choice = response == null ? null : response.path("choices").path(0);
            String content = choice == null ? null : choice.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new AiUnavailableException("DeepSeek 返回了空内容");
            }
            // finish_reason=length 表示响应被 max_tokens 截断，JSON 很可能不完整。
            if ("length".equals(choice.path("finish_reason").asText(""))) {
                log.warn("DeepSeek 响应因 max_tokens 上限被截断（jsonMode={}，maxTokens={}），"
                                + "可调大 deepseek.json-max-tokens / deepseek.chat-max-tokens。",
                        jsonMode, jsonMode ? properties.getJsonMaxTokens() : properties.getChatMaxTokens());
            }
            return content.trim();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            String reason = switch (status) {
                case 401 -> "DeepSeek API Key 无效或已过期";
                case 402 -> "DeepSeek 账户余额不足";
                case 429 -> "DeepSeek API 请求过于频繁，请稍后重试";
                default -> "DeepSeek 请求失败，HTTP " + status;
            };
            throw new AiUnavailableException(reason, exception);
        } catch (AiUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiUnavailableException("无法连接 DeepSeek API，请检查网络或稍后重试", exception);
        }
    }

    /**
     * 组装请求体。抽成 static 纯函数是为了**不依赖 Mockito 就能单测**
     * （Java 25 + byte-buddy 1.15.11 不兼容，本项目测试一律无 Mock 写法）。
     */
    static Map<String, Object> buildPayload(List<DeepSeekMessage> messages, boolean jsonMode,
                                            DeepSeekProperties properties) {
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        for (DeepSeekMessage message : messages) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            payloadMessages.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("messages", payloadMessages);
        payload.put("temperature", jsonMode ? 0.2 : 0.55);
        // 推理模型会把思维链 token 计入 max_tokens，因此 JSON 模式需要更大的输出预算。
        payload.put("max_tokens", jsonMode ? properties.getJsonMaxTokens() : properties.getChatMaxTokens());
        if (jsonMode) {
            payload.put("response_format", Map.of("type", "json_object"));
        }
        // 关思考：上游只认这种写法；enable_thinking 会被静默忽略（scripts/probe-thinking-param.py 实测）。
        if (properties.isDisableThinking()) {
            payload.put("thinking", Map.of("type", "disabled"));
        }
        return payload;
    }

    public JsonNode parseJson(String content) throws Exception {
        String normalized = content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "");
            normalized = normalized.replaceFirst("\\s*```$", "");
        }
        return objectMapper.readTree(normalized);
    }
}

