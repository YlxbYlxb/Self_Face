package com.selfface.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 解析大模型返回的 JSON。
 *
 * <p>即使明确要求「严格输出 JSON」，模型仍时常包一层 ```json 代码块，
 * 或在前后加一句「好的，以下是分析结果：」。这里统一做两层容错：
 * 先剥代码块，再截取第一个 <code>{</code> 到最后一个 <code>}</code>。
 *
 * <p>抽成公共组件是因为简历分析和 JD 分析都要用——分散在两处的话，
 * 容错逻辑一旦要调整就得改两遍，很容易只改一处。
 */
@Component
@RequiredArgsConstructor
public class LlmJson {

    private final ObjectMapper mapper;

    /** 读取 JSON，失败时抛出可直接展示给用户的异常（含原始输出片段，便于排查） */
    public JsonNode read(String content) {
        String cleaned = stripCodeFence(content);
        try {
            return mapper.readTree(cleaned);
        } catch (Exception e) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(cleaned.substring(start, end + 1));
                } catch (Exception ignored) {
                    // 落到下面统一抛错
                }
            }
            throw new LlmException("模型没有返回合法 JSON，请重试或换用指令遵循能力更强的模型。原始输出前 300 字："
                    + (content.length() > 300 ? content.substring(0, 300) : content));
        }
    }

    public String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new LlmException("序列化模型结果失败", e);
        }
    }

    static String stripCodeFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }
}
