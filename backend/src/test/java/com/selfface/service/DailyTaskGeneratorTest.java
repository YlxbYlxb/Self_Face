package com.selfface.service;

import com.selfface.common.BizException;
import com.selfface.entity.DailyTask;
import com.selfface.entity.Question;
import com.selfface.mapper.DailyTaskMapper;
import com.selfface.mapper.QuestionMapper;
import com.selfface.mapper.ReviewStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组卷算法的四条规则：到期复习优先、新题补充、分类均衡、兜底不空手。
 *
 * <p>这些用例守住的核心行为是：<b>到期复习题必须排在题单最前面，并且所占名额有上限</b>。
 * 它是 SM-2 调度能真正生效的前提——如果组卷不把到期题放进来，
 * 再准的间隔计算也白算。
 */
@ExtendWith(MockitoExtension.class)
class DailyTaskGeneratorTest {

    private static final long USER = 7L;

    @Mock
    private DailyTaskMapper dailyTaskMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private ReviewStateMapper reviewStateMapper;

    private DailyTaskGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new DailyTaskGenerator(dailyTaskMapper, questionMapper, reviewStateMapper);
    }

    @Test
    @DisplayName("到期复习题排在最前，新题补足剩余名额")
    void dueQuestionsComeFirstThenFreshOnes() {
        when(reviewStateMapper.dueQuestionIds(eq(USER), any(), anyInt()))
                .thenReturn(List.of(101L, 102L, 103L));
        List<Question> fresh = new ArrayList<>();
        for (long i = 201; i <= 204; i++) {
            fresh.add(question(i, 1L));
        }
        for (long i = 205; i <= 207; i++) {
            fresh.add(question(i, 2L));
        }
        when(questionMapper.selectList(any())).thenReturn(fresh);
        when(dailyTaskMapper.batchInsertIgnore(any())).thenReturn(10);

        generator.generate(USER, LocalDate.now());

        List<DailyTask> saved = capturedBatch();
        assertEquals(List.of(101L, 102L, 103L), questionIds(saved).subList(0, 3),
                "到期的三道题必须排在题单最前面");
        assertEquals(10, saved.size(), "剩余名额由新题补足到每日容量");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), seqs(saved), "序号必须从 1 连续");
        assertTrue(saved.stream().allMatch(t -> t.getMastery() == PracticeService.MASTERY_UNKNOWN),
                "新生成的题单全部是未作答状态");
    }

    @Test
    @DisplayName("到期复习题的名额上限是 REVIEW_QUOTA")
    void dueQueryIsCappedByReviewQuota() {
        when(reviewStateMapper.dueQuestionIds(eq(USER), any(), anyInt())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of(question(1, 1L)));
        when(dailyTaskMapper.batchInsertIgnore(any())).thenReturn(1);

        generator.generate(USER, LocalDate.now());

        verify(reviewStateMapper).dueQuestionIds(eq(USER), any(), eq(DailyTaskGenerator.REVIEW_QUOTA));
    }

    @Test
    @DisplayName("今天没有到期题时，题单全部由新题组成，且不触发兜底查询")
    void fallsBackToFreshQuestionsWhenNothingDue() {
        when(reviewStateMapper.dueQuestionIds(eq(USER), any(), anyInt())).thenReturn(List.of());
        List<Question> fresh = new ArrayList<>();
        for (long i = 11; i <= 20; i++) {
            fresh.add(question(i, i % 2 + 1));   // 两个分类交替，够填满每日容量
        }
        when(questionMapper.selectList(any())).thenReturn(fresh);
        when(dailyTaskMapper.batchInsertIgnore(any())).thenReturn(10);

        generator.generate(USER, LocalDate.now());

        assertEquals(10, questionIds(capturedBatch()).size(), "新题池充足时应当填满每日容量");
        verify(reviewStateMapper, never()).stalestQuestions(any(), anyCollection(), anyInt());
    }

    @Test
    @DisplayName("新题按分类轮转，不会一天十道全出自同一个分类")
    void freshQuestionsRotateAcrossCategories() {
        when(reviewStateMapper.dueQuestionIds(eq(USER), any(), anyInt())).thenReturn(List.of());
        List<Question> pool = new ArrayList<>();
        for (long i = 1; i <= 4; i++) {
            pool.add(question(i, 1L));       // 分类 1 四道
        }
        for (long i = 5; i <= 8; i++) {
            pool.add(question(i, 2L));       // 分类 2 四道
        }
        when(questionMapper.selectList(any())).thenReturn(pool);
        when(dailyTaskMapper.batchInsertIgnore(any())).thenReturn(8);

        generator.generate(USER, LocalDate.now());

        assertEquals(List.of(1L, 5L, 2L, 6L, 3L, 7L, 4L, 8L), questionIds(capturedBatch()),
                "应当两个分类交替取，而不是先取完分类 1 再取分类 2");
    }

    @Test
    @DisplayName("题库全做完且今天无到期题时，挑最久没复习的兜底，而不是空手")
    void fallsBackToStalestQuestionsWhenBankExhausted() {
        when(reviewStateMapper.dueQuestionIds(eq(USER), any(), anyInt())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(reviewStateMapper.stalestQuestions(eq(USER), anyCollection(), anyInt()))
                .thenReturn(List.of(question(31, 1L), question(32, 2L)));
        when(dailyTaskMapper.batchInsertIgnore(any())).thenReturn(2);

        generator.generate(USER, LocalDate.now());

        assertEquals(List.of(31L, 32L), questionIds(capturedBatch()));
        verify(dailyTaskMapper).batchInsertIgnore(any());
    }

    @Test
    @DisplayName("题库为空时抛出可读的业务异常，且不写库")
    void throwsReadableErrorWhenBankEmpty() {
        when(reviewStateMapper.dueQuestionIds(eq(USER), any(), anyInt())).thenReturn(List.of());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(reviewStateMapper.stalestQuestions(eq(USER), anyCollection(), anyInt()))
                .thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> generator.generate(USER, LocalDate.now()));

        assertTrue(e.getMessage().contains("题库"), "异常信息要能告诉用户发生了什么，实际：" + e.getMessage());
        verify(dailyTaskMapper, never()).batchInsertIgnore(any());
    }

    @SuppressWarnings("unchecked")
    private List<DailyTask> capturedBatch() {
        ArgumentCaptor<List<DailyTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(dailyTaskMapper).batchInsertIgnore(captor.capture());
        return captor.getValue();
    }

    private static List<Long> questionIds(List<DailyTask> tasks) {
        return tasks.stream().map(DailyTask::getQuestionId).toList();
    }

    private static List<Integer> seqs(List<DailyTask> tasks) {
        return tasks.stream().map(DailyTask::getSeq).toList();
    }

    private static Question question(long id, long categoryId) {
        Question q = new Question();
        q.setId(id);
        q.setCategoryId(categoryId);
        q.setTitle("题目 " + id);
        q.setDifficulty(2);
        q.setHot(1);
        return q;
    }
}
