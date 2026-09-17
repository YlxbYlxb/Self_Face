package com.selfface.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfface.common.BizException;
import com.selfface.entity.Category;
import com.selfface.entity.Question;
import com.selfface.mapper.CategoryMapper;
import com.selfface.mapper.QuestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导入是外部 JSON 进入题库的唯一入口，也是最容易灌进脏数据的地方：
 * 重复题、缺字段、分类不存在、难度越界、一次塞几千条。
 */
@ExtendWith(MockitoExtension.class)
class QuestionImportServiceTest {

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private CategoryMapper categoryMapper;

    private QuestionImportService service;
    private final AtomicLong categoryIdSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new QuestionImportService(questionMapper, categoryMapper, new ObjectMapper());
        lenient().when(categoryMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(questionMapper.selectList(any())).thenReturn(Collections.emptyList());
        // MyBatis-Plus 插入后会把自增主键回填到实体上，这里模拟同样的行为
        lenient().when(categoryMapper.insert(any(Category.class))).thenAnswer(inv -> {
            Category category = inv.getArgument(0);
            category.setId(categoryIdSeq.incrementAndGet());
            return 1;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Question> captureInsertedQuestions() {
        ArgumentCaptor<List<Question>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionMapper).batchInsert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("纯数组格式可以导入，未知分类自动创建")
    void importsPlainArray() {
        String json = """
                [ { "title": "什么是 JVM", "answer": "答案", "category": "java-jvm", "difficulty": 2 },
                  { "title": "GC 有哪几种", "answer": "答案", "category": "java-jvm", "difficulty": 3 } ]
                """;

        QuestionImportService.Result result = service.importJson(json, true);

        assertEquals(2, result.total());
        assertEquals(2, result.inserted());
        assertEquals(0, result.skipped());
        assertEquals(1, result.categoriesCreated(), "java-jvm 不存在，应当只建一个分类");
    }

    @Test
    @DisplayName("对象格式：先建 categories 里的分类，再挂题目")
    void importsObjectWithDeclaredCategories() {
        String json = """
                { "categories": [ { "code": "redis", "name": "Redis", "description": "缓存" } ],
                  "questions":  [ { "title": "Redis 为什么快", "category": "redis" } ] }
                """;

        QuestionImportService.Result result = service.importJson(json, true);

        assertEquals(1, result.inserted());
        assertEquals(1, result.categoriesCreated());
    }

    @Test
    @DisplayName("标题已存在时跳过，重复导入同一份文件不会灌进重复题")
    void skipsDuplicateTitles() {
        Question existing = new Question();
        existing.setTitle("什么是 JVM");
        when(questionMapper.selectList(any())).thenReturn(List.of(existing));

        String json = """
                [ { "title": "什么是 JVM", "category": "java-jvm" },
                  { "title": "全新的题目", "category": "java-jvm" } ]
                """;

        QuestionImportService.Result result = service.importJson(json, true);

        assertEquals(1, result.inserted());
        assertEquals(1, result.skipped());
        assertTrue(result.reasons().get(0).contains("已存在"));
    }

    @Test
    @DisplayName("同一批里的重复项也只保留一条")
    void dedupesWithinSameBatch() {
        String json = """
                [ { "title": "重复题", "category": "java-jvm" },
                  { "title": "重复题", "category": "java-jvm" } ]
                """;

        QuestionImportService.Result result = service.importJson(json, true);

        assertEquals(1, result.inserted());
        assertEquals(1, result.skipped());
    }

    @Test
    @DisplayName("关掉自动建分类时，未知分类的题目被跳过并说明原因")
    void skipsUnknownCategoryWhenAutoCreateDisabled() {
        String json = """
                [ { "title": "题目 A", "category": "不存在的分类" } ]
                """;

        QuestionImportService.Result result = service.importJson(json, false);

        assertEquals(0, result.inserted());
        assertEquals(1, result.skipped());
        assertEquals(0, result.categoriesCreated());
        assertTrue(result.reasons().get(0).contains("不存在"));
    }

    @Test
    @DisplayName("缺 title 的条目被跳过，不影响同批其它题")
    void skipsEntriesWithoutTitle() {
        String json = """
                [ { "answer": "没有题干", "category": "java-jvm" },
                  { "title": "正常题目", "category": "java-jvm" } ]
                """;

        QuestionImportService.Result result = service.importJson(json, true);

        assertEquals(1, result.inserted());
        assertEquals(1, result.skipped());
        assertTrue(result.reasons().get(0).contains("title"));
    }

    @Test
    @DisplayName("难度越界夹到 1-3，而不是整条拒收")
    void clampsOutOfRangeDifficulty() {
        String json = """
                [ { "title": "太难的题", "category": "java-jvm", "difficulty": 9 },
                  { "title": "太简单的题", "category": "java-jvm", "difficulty": -5 } ]
                """;

        service.importJson(json, true);
        List<Question> inserted = captureInsertedQuestions();

        assertEquals(3, inserted.get(0).getDifficulty());
        assertEquals(1, inserted.get(1).getDifficulty());
    }

    @Test
    @DisplayName("用中文展示名也能匹配到已有分类")
    void matchesExistingCategoryByDisplayName() {
        Category existing = new Category();
        existing.setId(7L);
        existing.setCode("java-basic");
        existing.setName("Java 基础");
        when(categoryMapper.selectList(any())).thenReturn(List.of(existing));

        String json = """
                [ { "title": "自动装箱", "category": "Java 基础" } ]
                """;

        QuestionImportService.Result result = service.importJson(json, true);

        assertEquals(1, result.inserted());
        assertEquals(0, result.categoriesCreated(), "分类已存在，不该重复创建");
    }

    @Test
    @DisplayName("自动创建的分类 code 必须是 ASCII，中文名用哈希兜底")
    void autoCreatedCategoryCodeIsAscii() {
        String json = """
                [ { "title": "一道题", "category": "Java 并发编程" } ]
                """;

        service.importJson(json, true);

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryMapper).insert(captor.capture());
        String code = captor.getValue().getCode();
        assertTrue(code.matches("[a-z0-9-]+"), "code 含有非法字符：" + code);
        assertEquals("Java 并发编程", captor.getValue().getName(), "展示名保留原文");
        assertFalse(code.contains(" "));
    }

    @Test
    @DisplayName("单次超过 500 条直接拒绝，提示分批导入")
    void rejectsTooManyQuestions() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < QuestionImportService.MAX_QUESTIONS_PER_IMPORT + 1; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"title\":\"题目").append(i).append("\",\"category\":\"java-jvm\"}");
        }
        json.append("]");

        BizException ex = assertThrows(BizException.class, () -> service.importJson(json.toString(), true));
        assertTrue(ex.getMessage().contains("分批"));
    }

    @Test
    @DisplayName("非法 JSON 给出可操作的提示，不泄露解析器内部信息")
    void rejectsInvalidJson() {
        BizException ex = assertThrows(BizException.class, () -> service.importJson("{ 这不是 JSON", true));

        assertTrue(ex.getMessage().contains("JSON"));
        assertFalse(ex.getMessage().contains("com.fasterxml"));
    }

    @Test
    @DisplayName("空内容与空数组都拒绝")
    void rejectsEmptyInput() {
        assertThrows(BizException.class, () -> service.importJson("", true));
        assertThrows(BizException.class, () -> service.importJson("[]", true));
    }
}
