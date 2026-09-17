package com.selfface.service;

import com.selfface.common.BizException;
import com.selfface.entity.LlmSetting;
import com.selfface.entity.Question;
import com.selfface.llm.JdAnalyzer;
import com.selfface.mapper.QuestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JD 定向题单的汇总逻辑。
 *
 * <p>重点钉住两件事：<b>覆盖率按「有题可刷的关键词占比」算</b>（而不是命中题目数），
 * 以及<b>被多个关键词命中的题要排在前面</b>——它才是这份 JD 的核心考点。
 */
@ExtendWith(MockitoExtension.class)
class JdMatchServiceTest {

    @Mock
    private JdAnalyzer jdAnalyzer;
    @Mock
    private LlmSettingService llmSettingService;
    @Mock
    private QuestionMapper questionMapper;

    private JdMatchService service;

    @BeforeEach
    void setUp() {
        service = new JdMatchService(jdAnalyzer, llmSettingService, questionMapper);
    }

    @Test
    @DisplayName("JD 太短时直接拒绝，不浪费一次模型调用")
    void rejectsTooShortJd() {
        BizException e = assertThrows(BizException.class, () -> service.analyze(1L, "招 Java 开发", null));

        assertTrue(e.getMessage().contains("太短"), "异常信息要说明原因，实际：" + e.getMessage());
        verifyNoInteractions(jdAnalyzer, llmSettingService);
    }

    @Test
    @DisplayName("按关键词召回：统计命中数、识别缺失关键词、优先推荐多词命中的题")
    void aggregatesKeywordHits() {
        when(llmSettingService.requireConfigured(1L)).thenReturn(new LlmSetting());
        when(jdAnalyzer.extract(any(), anyString(), any())).thenReturn(new JdAnalyzer.JdProfile(
                "Java 后端开发", "看重基础与中间件",
                List.of(
                        new JdAnalyzer.Keyword("Spring Boot", "框架", "核心"),
                        new JdAnalyzer.Keyword("Redis", "中间件", "核心"),
                        new JdAnalyzer.Keyword("Kafka", "中间件", "加分"))));

        when(questionMapper.searchByKeyword(eq("Spring Boot"), anyInt()))
                .thenReturn(List.of(question(1L, "Spring Boot 自动配置原理", 1),
                        question(2L, "Spring 事务传播机制", 0)));
        when(questionMapper.searchByKeyword(eq("Redis"), anyInt()))
                .thenReturn(List.of(question(2L, "Spring 事务传播机制", 0)));
        when(questionMapper.searchByKeyword(eq("Kafka"), anyInt()))
                .thenReturn(List.of());

        Map<String, Object> result = service.analyze(1L, "岗位描述".repeat(20), "Java 后端");

        assertEquals("Java 后端开发", result.get("role"));
        assertEquals(3, result.get("keywordCount"));
        assertEquals(2, result.get("coveredCount"), "Spring Boot 与 Redis 有题，Kafka 没有");
        assertEquals(67L, result.get("coverage"), "2/3 应为 67%");
        assertEquals(List.of("Kafka"), result.get("missingKeywords"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recommended = (List<Map<String, Object>>) result.get("recommended");
        assertEquals(2, recommended.size(), "同一道题被多个关键词命中时只出现一次");
        assertEquals(2L, recommended.get(0).get("questionId"),
                "命中两个关键词的题应当排在只命中一个的前面");
        assertEquals(List.of("Spring Boot", "Redis"), recommended.get(0).get("hitTerms"));
    }

    @Test
    @DisplayName("关键词全部没命中时覆盖率为 0，不抛异常")
    void zeroCoverageIsNotAnError() {
        when(llmSettingService.requireConfigured(1L)).thenReturn(new LlmSetting());
        when(jdAnalyzer.extract(any(), anyString(), any())).thenReturn(new JdAnalyzer.JdProfile(
                "算法工程师", "偏模型",
                List.of(new JdAnalyzer.Keyword("Transformer", "方法论", "核心"))));
        when(questionMapper.searchByKeyword(eq("Transformer"), anyInt())).thenReturn(List.of());

        Map<String, Object> result = service.analyze(1L, "岗位描述".repeat(20), null);

        assertEquals(0L, result.get("coverage"));
        assertEquals(1, result.get("keywordCount"));
        assertEquals(List.of(), result.get("recommended"));
    }

    private static Question question(long id, String title, int hot) {
        Question q = new Question();
        q.setId(id);
        q.setTitle(title);
        q.setTags("Java,Spring");
        q.setDifficulty(2);
        q.setHot(hot);
        return q;
    }
}
