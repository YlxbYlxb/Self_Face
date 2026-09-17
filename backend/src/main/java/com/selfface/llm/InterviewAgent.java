package com.selfface.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfface.entity.InterviewTurn;
import com.selfface.entity.LlmSetting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟面试官。
 *
 * <p>和「出题」类 AI 功能最大的区别是：这里刻意<b>不让模型给出答案</b>。
 * 刷题时你可以看着参考答案自评「我懂」，但面试现场没有参考答案，
 * 必须自己组织语言把话说清楚。所以面试官只提问、只追问、只点评，
 * 参考答案留到最后报告之外的地方（题库）去看。
 *
 * <p>三个动作：开场提问、评价回答并追问、生成总结报告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewAgent {

    private final LlmClient llmClient;
    private final LlmJson llmJson;

    /** 送进 prompt 的简历参考问题上限，防止简历里问题太多把上下文撑爆 */
    private static final int MAX_SEED_QUESTIONS = 10;

    private static final String SYSTEM = """
            你是一位在中国互联网公司做了十年技术面试的面试官，正在对候选人做一场模拟面试。
            铁律：
            1. 你只提问和追问，绝不给出答案、提示或解题思路。候选人必须自己在没有参考答案的情况下把话说清楚。
            2. 每次只问一个问题，不要一次抛出好几问。
            3. 根据回答决定下一步：答得含糊就追问细节，答得扎实就换更深的点或换个方向；
               如果候选人明显答不上来，点一句「这个我们先跳过」，然后换个相关但更基础的问题。
            4. 点评必须具体：说清答对了哪一点、漏了哪一点。禁止「回答得不错」「基本正确」这类空话。
            5. 追问要基于候选人原话里的破绽或含糊之处，而不是另起一个无关问题。
            6. 严格输出 JSON，不要任何解释性文字，不要 Markdown 代码块。
            """;

    private static final String OPENING_SCHEMA = """
            输出 JSON 结构：
            {
              "question": "开场问题，尽量贴合候选人的简历或目标岗位",
              "kind": "question"
            }
            """;

    private static final String REPLY_SCHEMA = """
            输出 JSON 结构：
            {
              "score": 0,
              "comment": "对刚才这个回答的点评，100 字以内，具体指出对在哪、缺什么",
              "gaps": ["这轮暴露出的知识盲点，0-3 条，没有就给空数组"],
              "nextQuestion": "下一个问题：可以是追问，也可以换一个新方向",
              "kind": "follow_up 或 question"
            }
            """;

    private static final String REPORT_SCHEMA = """
            输出 JSON 结构：
            {
              "overallScore": 0,
              "verdict": "一句话总评，60 字以内，直接说明能不能过这场面试",
              "dimensions": [
                {"name": "维度名，如 基础知识 / 表达结构 / 项目深度 / 临场反应", "score": 0, "comment": "30 字以内"}
              ],
              "strengths": ["表现出的优势，0-3 条"],
              "weaknesses": [
                {"point": "暴露的问题", "suggestion": "具体怎么补，尽量落到可执行的动作"}
              ],
              "suggestedTopics": ["建议接下来优先复习的知识点"]
            }
            """;

    /** 面试背景，一次会话内不变 */
    public record Context(String role, String level, String type,
                          String resumeBrief, List<String> seedQuestions) {
    }

    public record Opening(String question, String kind) {
    }

    public record Evaluation(int score, String comment, List<String> gaps,
                             String nextQuestion, String kind) {
    }

    public Opening start(LlmSetting cfg, Context ctx) {
        String prompt = contextBlock(ctx) + "\n请提出你的第一个问题。\n\n" + OPENING_SCHEMA;
        JsonNode root = llmJson.read(llmClient.chat(cfg, SYSTEM, prompt, true).content());
        String question = root.path("question").asText("").trim();
        if (question.isEmpty()) {
            throw new LlmException("面试官没能提出第一个问题，请重试。");
        }
        return new Opening(question, InterviewTurn.KIND_QUESTION);
    }

    /**
     * 评价候选人的回答，并给出下一个问题。
     */
    public Evaluation reply(LlmSetting cfg, Context ctx, List<InterviewTurn> history, String answer) {
        StringBuilder sb = new StringBuilder(contextBlock(ctx));
        sb.append("\n【面试记录】\n").append(transcript(history)).append('\n');
        sb.append("\n候选人刚刚的回答：\n").append(answer).append('\n');
        sb.append("\n请点评这个回答，并提出下一个问题。\n\n").append(REPLY_SCHEMA);

        JsonNode root = llmJson.read(llmClient.chat(cfg, SYSTEM, sb.toString(), true).content());

        List<String> gaps = new ArrayList<>();
        for (JsonNode g : root.path("gaps")) {
            String s = g.asText("").trim();
            if (!s.isEmpty()) {
                gaps.add(s);
            }
        }
        String next = root.path("nextQuestion").asText("").trim();
        String kind = root.path("kind").asText(InterviewTurn.KIND_QUESTION).trim();
        if (!InterviewTurn.KIND_FOLLOW_UP.equals(kind)) {
            kind = InterviewTurn.KIND_QUESTION;
        }
        return new Evaluation(
                clampScore(root.path("score").asInt(0)),
                root.path("comment").asText("").trim(),
                gaps,
                next,
                kind);
    }

    /**
     * 生成总结报告。返回原始 JSON，由前端按字段渲染。
     */
    public JsonNode report(LlmSetting cfg, Context ctx, List<InterviewTurn> history) {
        StringBuilder sb = new StringBuilder(contextBlock(ctx));
        sb.append("\n【完整面试记录】\n").append(transcript(history)).append('\n');
        sb.append("""

                这场模拟面试到此结束。请以面试官身份给出评估报告。
                注意：分数要与候选人的实际表现一致，不要为了鼓励而虚高——
                虚高的分数会让候选人误判自己的准备程度。

                """).append(REPORT_SCHEMA);

        return llmJson.read(llmClient.chat(cfg, SYSTEM, sb.toString(), true).content());
    }

    /** 面试背景：岗位、级别、类型，加上简历里的参考问题 */
    private String contextBlock(Context ctx) {
        StringBuilder sb = new StringBuilder("【面试背景】\n");
        sb.append("目标岗位：").append(dash(ctx.role())).append('\n');
        sb.append("面试级别：").append(levelName(ctx.level())).append('\n');
        sb.append("面试类型：").append(typeName(ctx.type())).append('\n');

        if (ctx.resumeBrief() != null && !ctx.resumeBrief().isBlank()) {
            sb.append("\n【候选人简历摘要】\n").append(ctx.resumeBrief()).append('\n');
        }
        List<String> seeds = ctx.seedQuestions();
        if (seeds != null && !seeds.isEmpty()) {
            sb.append("\n【可参考的提问方向】\n");
            sb.append("（这些是基于候选人简历预判的考点，优先从中出题，但不必全用）\n");
            int limit = Math.min(seeds.size(), MAX_SEED_QUESTIONS);
            for (int i = 0; i < limit; i++) {
                sb.append(i + 1).append(". ").append(seeds.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String transcript(List<InterviewTurn> history) {
        StringBuilder sb = new StringBuilder();
        for (InterviewTurn t : history) {
            sb.append(InterviewTurn.ROLE_INTERVIEWER.equals(t.getRole()) ? "面试官" : "候选人")
                    .append("：").append(t.getContent())
                    .append('\n');
        }
        return sb.toString();
    }

    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private static String levelName(String level) {
        if (level == null) {
            return "初级（实习/校招）";
        }
        return switch (level) {
            case "mid" -> "中级（1-3 年）";
            case "senior" -> "高级（3 年以上）";
            default -> "初级（实习/校招）";
        };
    }

    private static String typeName(String type) {
        if (type == null) {
            return "技术面";
        }
        return switch (type) {
            case "project" -> "项目面（深挖简历项目）";
            case "comprehensive" -> "综合面（技术 + 软素质）";
            default -> "技术面";
        };
    }

    private static String dash(String s) {
        return (s == null || s.isBlank()) ? "未指定" : s;
    }
}
