package com.selfface.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfface.entity.LlmSetting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * JD 分析：把一份岗位描述拆成可检索的技术关键词。
 *
 * <p>为什么先抽词、再检索，而不是直接把 JD 丢给模型让它推荐题目：
 * 抽出来的关键词是<b>可核对的中间产物</b>——用户能看到「匹配上了什么、缺什么」，
 * 也能看出模型有没有胡说。直接让模型推荐题目的话，它给出的题目 id 很可能是编的。
 *
 * <p>抽取的范围限制在真实出现的名词，是为了让「缺失关键词」这一项有意义：
 * 如果模型开始脑补 JD 里没写的技术，"缺失"就变成了它自己想象的产物。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdAnalyzer {

    private final LlmClient llmClient;
    private final LlmJson llmJson;

    /** 关键词数量上限：每个词都要查一次库，太多会拖慢响应 */
    static final int MAX_KEYWORDS = 20;
    /** 送进模型的 JD 正文上限，防止有人粘贴一整本岗位合集 */
    static final int MAX_JD_CHARS = 8000;

    private static final String SYSTEM = """
            你是一位在中国互联网公司做技术招聘的面试官，负责把岗位 JD 拆成可执行的技术考点清单。
            要求：
            1. 只抽取 JD 里真实提到的技术名词（语言、框架、中间件、数据库、工具、方法论）。
               不要臆测 JD 没写的技术，也不要把「沟通能力强」「有责任心」这类软性要求当成技术关键词。
            2. 关键词要具体到能检索的程度：「Spring Boot」而不是「Java 开发」，
               「MySQL 索引」而不是「数据库」，「Redis 缓存穿透」而不是「缓存」。
            3. weight 表示 JD 里的重要程度：写「精通 / 负责 / 必须 / 熟练掌握」的算「核心」，
               写「了解 / 加分项 / 优先考虑」的算「加分」。
            4. 最多输出 %d 个关键词，按重要程度从高到低排列。
            5. 严格输出 JSON，不要任何解释性文字，不要 Markdown 代码块。
            """.formatted(MAX_KEYWORDS);

    private static final String SCHEMA = """
            输出 JSON 结构：
            {
              "role": "从 JD 提炼出的岗位名",
              "summary": "一句话概括这个岗位最看重什么，60 字以内",
              "keywords": [
                {
                  "term": "技术关键词",
                  "category": "语言/框架/数据库/中间件/工具/方法论",
                  "weight": "核心/加分"
                }
              ]
            }
            """;

    /** 一个技术关键词 */
    public record Keyword(String term, String category, String weight) {
    }

    /** 从 JD 里抽出的画像 */
    public record JdProfile(String role, String summary, List<Keyword> keywords) {
    }

    /**
     * 抽取 JD 的技术关键词。
     *
     * @param roleHint 用户在资料里填的目标岗位，可能和这份 JD 不一致，仅供模型参考
     */
    public JdProfile extract(LlmSetting cfg, String jdText, String roleHint) {
        String prompt = """
                用户自填的目标岗位（仅供参考，与下面的 JD 冲突时以 JD 为准）：%s

                请阅读下面的岗位 JD，抽取技术考点清单。

                【JD 原文】
                %s

                %s
                """.formatted(blankToDash(roleHint), truncate(jdText), SCHEMA);

        LlmResponse resp = llmClient.chat(cfg, SYSTEM, prompt, true);
        JsonNode root = llmJson.read(resp.content());

        List<Keyword> keywords = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : root.path("keywords")) {
            String term = node.path("term").asText("").trim();
            // 过长的几乎肯定是句子而不是关键词，检索时也匹不上任何东西
            if (term.isEmpty() || term.length() > 40) {
                continue;
            }
            if (!seen.add(term.toLowerCase(Locale.ROOT))) {
                continue;
            }
            keywords.add(new Keyword(
                    term,
                    node.path("category").asText("其他").trim(),
                    node.path("weight").asText("核心").trim()));
            if (keywords.size() >= MAX_KEYWORDS) {
                break;
            }
        }

        if (keywords.isEmpty()) {
            throw new LlmException("没能从这份 JD 里提取出技术关键词。"
                    + "请确认粘贴的是岗位描述（含技术栈要求），而不是公司介绍或招聘流程说明。");
        }
        log.info("JD 抽取到 {} 个技术关键词", keywords.size());
        return new JdProfile(
                root.path("role").asText("").trim(),
                root.path("summary").asText("").trim(),
                keywords);
    }

    private static String truncate(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() <= MAX_JD_CHARS ? t : t.substring(0, MAX_JD_CHARS) + "\n…（JD 过长已截断）";
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "未填写" : s;
    }
}
