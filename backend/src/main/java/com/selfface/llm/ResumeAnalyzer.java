package com.selfface.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfface.entity.LlmSetting;
import com.selfface.entity.Question;
import com.selfface.entity.User;
import com.selfface.mapper.QuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 简历分析 Agent：两阶段编排。
 *
 * Stage 1 —— 结构化：把简历原文压成一份 JSON 画像（技能、项目、亮点、风险点）。
 * Stage 2 —— 出题：拿画像去题库检索相关考点，一并交给模型，产出「大概率会被问到」的问题清单与追问链。
 *
 * 拆两步而不是一步到位，是因为画像可以复用（再次出题不必重解析简历），
 * 而且单次 prompt 更短，模型跑偏的概率更低。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAnalyzer {

    private final LlmClient llmClient;
    private final QuestionMapper questionMapper;
    private final LlmJson llmJson;

    private static final String PROFILE_SYSTEM = """
            你是一位在中国互联网公司做了十年技术面试的面试官，面过大量校招与实习候选人。
            你的任务是阅读一份简历，抽取结构化画像。要求：
            1. 只提取简历中真实出现的信息，不要脑补、不要美化。简历没写的技能不要列。
            2. 「风险点」要写面试官视角的疑点，例如：项目描述缺少量化结果、技术栈与岗位不匹配、
               时间线有跳跃、写了「精通」但没有支撑证据。
            3. 严格输出 JSON，不要任何解释性文字，不要 Markdown 代码块。
            """;

    private static final String PROFILE_SCHEMA = """
            输出 JSON 结构：
            {
              "name": "姓名，未提及则为空字符串",
              "education": "学历与院校专业，含起止时间",
              "targetRole": "从简历推断的目标岗位",
              "yearsOfExperience": "应届 / X 年",
              "skills": [{"name": "技能名", "level": "精通/熟悉/了解（按简历原话）", "evidence": "简历里支撑这一条的具体内容"}],
              "projects": [{"name": "项目名", "role": "担任角色", "techStack": ["技术1", "技术2"], "highlights": ["可被追问的亮点"], "weakPoints": ["面试官可能质疑的点"]}],
              "strengths": ["相对目标岗位的竞争优势"],
              "risks": ["面试中容易被问住的地方"]
            }
            """;

    private static final String QUESTION_SYSTEM = """
            你是一位资深技术面试官，正在为一位候选人准备面试问题。
            要求：
            1. 问题必须和候选人的简历强相关，问「他写过的东西」，而不是泛泛的八股。
            2. 每个问题给出：考察点、为什么针对这份简历问它、2-3 个追问、以及参考答案要点。
            3. 参考答案要具体到技术细节，不要正确的废话。
            4. 如果提供了「题库参考」，优先复用其中的题目（在 bankQuestionId 字段回填对应 id），
               但必须补充针对该候选人简历的追问角度。
            5. 严格输出 JSON，不要任何解释性文字，不要 Markdown 代码块。
            """;

    private static final String QUESTION_SCHEMA = """
            输出 JSON 结构：
            {
              "summary": "对这份简历的一句话总评（60 字以内）",
              "interviewFocus": ["这场面试最可能围绕的 3-4 个主题"],
              "groups": [
                {
                  "name": "考察分组名，如 Java 并发 / 项目深挖 / 数据库",
                  "reason": "为什么针对这份简历问这一组",
                  "questions": [
                    {
                      "question": "问题原文",
                      "type": "基础 / 深挖 / 场景 / 项目",
                      "difficulty": 1,
                      "why": "面试官问这个想验证什么",
                      "followUps": ["追问1", "追问2"],
                      "keyPoints": ["参考答案要点1", "要点2"],
                      "bankQuestionId": 0
                    }
                  ]
                }
              ],
              "preparationPlan": ["建议的复习优先级，按顺序列出"]
            }
            """;

    public AnalyzeResult analyze(LlmSetting cfg, User user, String resumeText) {
        JsonNode profile = extractProfile(cfg, user, resumeText);

        List<Question> related = searchBank(profile);
        log.info("简历画像命中题库 {} 道", related.size());

        LlmResponse q = llmClient.chat(cfg, QUESTION_SYSTEM,
                buildQuestionPrompt(profile, related, user), true);

        JsonNode questions = llmJson.read(q.content());
        String summary = questions.path("summary").asText("");

        return new AnalyzeResult(
                llmJson.write(profile),
                llmJson.write(questions),
                summary,
                q,
                related.size());
    }

    private JsonNode extractProfile(LlmSetting cfg, User user, String resumeText) {
        String prompt = """
                目标岗位：%s
                目标城市：%s

                请分析下面这份简历并输出画像 JSON。

                %s

                【简历原文】
                %s
                """.formatted(
                blankToDash(user.getTargetPosition()),
                blankToDash(user.getTargetCities()),
                PROFILE_SCHEMA,
                resumeText);

        LlmResponse resp = llmClient.chat(cfg, PROFILE_SYSTEM, prompt, true);
        return llmJson.read(resp.content());
    }

    /**
     * 把画像里的技能名与项目技术栈当作关键词，回题库做模糊检索，作为模型出题时的锚点。
     */
    private List<Question> searchBank(JsonNode profile) {
        Set<String> keywords = new LinkedHashSet<>();
        for (JsonNode skill : profile.path("skills")) {
            String name = skill.path("name").asText("").trim();
            if (name.length() >= 2 && name.length() <= 20) {
                keywords.add(name);
            }
        }
        for (JsonNode project : profile.path("projects")) {
            for (JsonNode tech : project.path("techStack")) {
                String t = tech.asText("").trim();
                if (t.length() >= 2 && t.length() <= 20) {
                    keywords.add(t);
                }
            }
        }
        if (keywords.isEmpty()) {
            return List.of();
        }

        QueryWrapper<Question> qw = new QueryWrapper<>();
        qw.and(w -> {
            boolean first = true;
            for (String kw : keywords) {
                if (first) {
                    w.like("title", kw).or().like("tags", kw);
                    first = false;
                } else {
                    w.or().like("title", kw).or().like("tags", kw);
                }
            }
        });
        qw.orderByDesc("hot").last("LIMIT 40");
        List<Question> list = questionMapper.selectList(qw);
        return list == null ? List.of() : list;
    }

    private String buildQuestionPrompt(JsonNode profile, List<Question> related, User user) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标岗位：").append(blankToDash(user.getTargetPosition())).append('\n');
        sb.append("目标城市：").append(blankToDash(user.getTargetCities())).append("\n\n");
        sb.append("【候选人画像 JSON】\n").append(llmJson.write(profile)).append("\n\n");

        sb.append("【题库参考】\n");
        if (related.isEmpty()) {
            sb.append("（题库暂无匹配题目，请基于简历自行出题，bankQuestionId 填 0）\n");
        } else {
            for (Question q : related) {
                sb.append("- id=").append(q.getId())
                        .append(" [").append(difficultyName(q.getDifficulty())).append("] ")
                        .append(q.getTitle());
                if (q.getTags() != null && !q.getTags().isBlank()) {
                    sb.append("（标签：").append(q.getTags()).append("）");
                }
                sb.append('\n');
            }
        }

        sb.append('\n').append(QUESTION_SCHEMA);
        sb.append("\n请输出 3 到 5 个分组，每组 3 到 5 道题，按现场被问到的可能性从高到低排列。");
        return sb.toString();
    }

    private static String difficultyName(Integer d) {
        if (d == null) {
            return "中等";
        }
        return switch (d) {
            case 1 -> "简单";
            case 3 -> "困难";
            default -> "中等";
        };
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "未填写" : s;
    }

    public record AnalyzeResult(
            String profileJson,
            String questionsJson,
            String summary,
            LlmResponse usage,
            int bankHitCount) {
    }

    /**
     * 从画像里取出用于前端展示的技能标签。
     */
    public List<String> skillNames(JsonNode profile) {
        List<String> names = new ArrayList<>();
        for (JsonNode s : profile.path("skills")) {
            names.add(s.path("name").asText(""));
        }
        return names;
    }
}
