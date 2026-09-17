package com.selfface.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.selfface.common.BizException;
import com.selfface.entity.LlmSetting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 极简 OpenAI 兼容客户端（POST {baseUrl}/chat/completions）。
 * 不做流式——简历分析是一次性任务，非流式实现更简单、更好排查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param jsonMode 尝试开启 JSON 输出。部分服务不支持该字段，失败时会自动去掉重试一次。
     */
    public LlmResponse chat(LlmSetting cfg, String systemPrompt, String userPrompt, boolean jsonMode) {
        requireConfigured(cfg);
        try {
            return doChat(cfg, systemPrompt, userPrompt, jsonMode);
        } catch (LlmException e) {
            // 服务端不认识 response_format，降级为普通文本模式再试一次
            if (jsonMode && e.getMessage() != null && e.getMessage().contains("response_format")) {
                log.warn("该模型不支持 response_format，降级重试");
                return doChat(cfg, systemPrompt, userPrompt, false);
            }
            throw e;
        }
    }

    private LlmResponse doChat(LlmSetting cfg, String systemPrompt, String userPrompt, boolean jsonMode) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("temperature", cfg.getTemperature() == null ? 0.3 : cfg.getTemperature());
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", userPrompt);

        if (jsonMode) {
            body.putObject("response_format").put("type", "json_object");
        }

        int timeout = cfg.getTimeoutSeconds() == null ? 120 : cfg.getTimeoutSeconds();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBase(cfg.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new LlmException("请求构造失败，请检查 Base URL 是否填写正确：" + cfg.getBaseUrl(), e);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException("模型响应超时（" + timeout + " 秒）。简历较长时可以适当调大超时时间，或换更快的模型。");
        } catch (Exception e) {
            // ConnectException 这类异常的 getMessage() 可能是 null，直接拼会得到「无法连接模型服务：null」
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            throw new LlmException("无法连接模型服务（" + reason + "）。请确认 Base URL 与本机网络可达。", e);
        }

        if (resp.statusCode() == 401) {
            throw new LlmException("API Key 无效或已过期，请在设置页重新填写。");
        }
        if (resp.statusCode() == 404) {
            throw new LlmException("接口路径 404。请确认 Base URL 形如 https://api.deepseek.com/v1（需要带 /v1），模型名 " + cfg.getModel() + " 是否存在。");
        }
        if (resp.statusCode() == 429) {
            throw new LlmException("触发服务端限流（429），稍后重试或换用其他 Key。");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new LlmException("模型返回 " + resp.statusCode() + "：" + truncate(resp.body(), 400));
        }

        try {
            JsonNode root = mapper.readTree(resp.body());
            JsonNode choice = root.path("choices").path(0).path("message").path("content");
            if (choice.isMissingNode() || choice.isNull()) {
                throw new LlmException("模型返回内容为空：" + truncate(resp.body(), 400));
            }
            JsonNode usage = root.path("usage");
            return new LlmResponse(
                    choice.asText(),
                    root.path("model").asText(cfg.getModel()),
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("解析模型返回失败：" + truncate(resp.body(), 400), e);
        }
    }

    private void requireConfigured(LlmSetting cfg) {
        if (cfg == null || isBlank(cfg.getBaseUrl()) || isBlank(cfg.getApiKey()) || isBlank(cfg.getModel())) {
            throw new BizException("还没有配置大模型。请到「设置」页填入 Base URL、API Key 和模型名后再使用 AI 功能。");
        }
    }

    static String normalizeBase(String baseUrl) {
        String b = baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
