package com.selfface.service;

import com.selfface.common.BizException;
import com.selfface.entity.LlmSetting;
import com.selfface.entity.Question;
import com.selfface.llm.JdAnalyzer;
import com.selfface.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JD 定向题单：把一份岗位描述变成「我该刷哪些题」。
 *
 * <p>与简历分析的分工：简历分析回答「面试官会问我什么」，本服务回答
 * <b>「这个岗位要什么，我的题库里有没有对应的训练」</b>。
 *
 * <p>链路：JD → 抽技术关键词（{@link JdAnalyzer}）→ 逐词召回题库 → 汇总命中情况。
 * 中间产物（每个词命中几道题、哪些词完全没题）都会返回给前端，
 * 这样用户能看见「我的题库在这个方向上有没有覆盖」，而不是只拿到一堆题目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdMatchService {

    /** 单个关键词最多召回多少题：召回太多会把推荐列表塞满同一个考点 */
    private static final int PER_KEYWORD_LIMIT = 30;
    /** 推荐题单容量上限 */
    private static final int RECOMMEND_LIMIT = 30;
    /** 接受粘贴的 JD 正文上限（字符） */
    private static final int MAX_JD_CHARS = 20000;
    /** 太短的输入基本不可能是 JD，提前拦下，省一次模型调用 */
    private static final int MIN_JD_CHARS = 30;

    private final JdAnalyzer jdAnalyzer;
    private final LlmSettingService llmSettingService;
    private final QuestionMapper questionMapper;

    /**
     * 分析一份 JD，返回关键词命中情况与推荐题单。
     */
    public Map<String, Object> analyze(Long userId, String jdText, String roleHint) {
        String jd = jdText == null ? "" : jdText.trim();
        if (jd.length() < MIN_JD_CHARS) {
            throw new BizException("JD 内容太短，请粘贴完整的岗位描述（至少 " + MIN_JD_CHARS + " 个字）");
        }
        if (jd.length() > MAX_JD_CHARS) {
            jd = jd.substring(0, MAX_JD_CHARS);
        }

        LlmSetting cfg = llmSettingService.requireConfigured(userId);
        JdAnalyzer.JdProfile profile = jdAnalyzer.extract(cfg, jd, roleHint);

        // 逐关键词召回，同时按题目去重、记录每道题被哪些关键词命中
        Map<Long, Question> hitQuestions = new LinkedHashMap<>();
        Map<Long, List<String>> hitTerms = new LinkedHashMap<>();
        List<Map<String, Object>> keywordRows = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (JdAnalyzer.Keyword kw : profile.keywords()) {
            List<Question> found = questionMapper.searchByKeyword(kw.term(), PER_KEYWORD_LIMIT);
            for (Question q : found) {
                hitQuestions.putIfAbsent(q.getId(), q);
                hitTerms.computeIfAbsent(q.getId(), k -> new ArrayList<>()).add(kw.term());
            }
            if (found.isEmpty()) {
                missing.add(kw.term());
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", kw.term());
            row.put("category", kw.category());
            row.put("weight", kw.weight());
            row.put("matchCount", found.size());
            keywordRows.add(row);
        }

        // 推荐排序：命中关键词越多越贴近这份 JD；同样命中数时高频考点优先
        List<Question> recommended = hitQuestions.values().stream()
                .sorted(Comparator
                        .comparingInt((Question q) -> -hitTerms.getOrDefault(q.getId(), List.of()).size())
                        .thenComparingInt(q -> q.getHot() == null ? 0 : -q.getHot())
                        .thenComparingLong(Question::getId))
                .limit(RECOMMEND_LIMIT)
                .toList();

        List<Map<String, Object>> recommendRows = new ArrayList<>();
        for (Question q : recommended) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", q.getId());
            item.put("title", q.getTitle());
            item.put("difficulty", q.getDifficulty());
            item.put("tags", q.getTags());
            item.put("hot", q.getHot());
            item.put("hitTerms", hitTerms.getOrDefault(q.getId(), List.of()));
            recommendRows.add(item);
        }

        int total = profile.keywords().size();
        int covered = total - missing.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", profile.role().isBlank() ? "未识别" : profile.role());
        result.put("summary", profile.summary());
        result.put("keywords", keywordRows);
        result.put("missingKeywords", missing);
        result.put("coveredCount", covered);
        result.put("keywordCount", total);
        result.put("coverage", total == 0 ? 0 : Math.round(covered * 100.0 / total));
        result.put("recommended", recommendRows);
        log.info("用户 {} 的 JD 分析完成：{} 个关键词，覆盖 {} 个，推荐 {} 道题",
                userId, total, covered, recommendRows.size());
        return result;
    }
}
