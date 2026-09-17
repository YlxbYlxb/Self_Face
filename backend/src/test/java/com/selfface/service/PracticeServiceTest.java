package com.selfface.service;

import com.selfface.entity.DailyTask;
import com.selfface.entity.Question;
import com.selfface.entity.ReviewState;
import com.selfface.mapper.DailyTaskMapper;
import com.selfface.mapper.PracticeRecordMapper;
import com.selfface.mapper.QuestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组卷与统计的行为约束。重点钉住三件容易改坏的事：
 * 热路径不再全量拉取、序号用 MAX(seq)、统计在 SQL 里聚合。
 *
 * <p>SM-2 之后新增的约束是第四条：<b>今日题单的「是不是复习题」必须来自 review_state，
 * 而不是去 practice_record 里聚合明细</b>——后者随着刷题量增长会越来越慢。
 */
@ExtendWith(MockitoExtension.class)
class PracticeServiceTest {

    @Mock
    private DailyTaskMapper dailyTaskMapper;
    @Mock
    private PracticeRecordMapper practiceRecordMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private DailyTaskGenerator dailyTaskGenerator;
    @Mock
    private ReviewStateService reviewStateService;

    private PracticeService service;

    @BeforeEach
    void setUp() {
        service = new PracticeService(dailyTaskMapper, practiceRecordMapper, questionMapper,
                dailyTaskGenerator, reviewStateService);
    }

    @Test
    @DisplayName("今日题单只查当天题目的调度状态，不再全量拉取作答记录")
    void todayTasksOnlyQueriesTodayQuestionIds() {
        LocalDate today = LocalDate.now();
        when(dailyTaskMapper.selectList(any()))
                .thenReturn(List.of(task(10L, 1, 1L, today), task(11L, 2, 2L, today)));
        when(questionMapper.selectBatchIds(any()))
                .thenReturn(List.of(question(1L, "HashMap 的扩容机制"), question(2L, "MySQL 索引失效场景")));
        when(reviewStateService.statesOf(any(), any()))
                .thenReturn(Map.of(2L, reviewState(2L, 3)));

        List<Map<String, Object>> out = service.todayTasks(9L);

        assertEquals(2, out.size());
        assertFalse((Boolean) out.get(0).get("isReview"), "第 1 题没有调度状态，是新题");
        assertTrue((Boolean) out.get(1).get("isReview"), "第 2 题有调度状态，属于复习");
        assertEquals(3, out.get(1).get("intervalDays"),
                "复习题要带上当前间隔，前端才能提示「下次 3 天后」");
        verify(practiceRecordMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("题单为空时交给生成器，并在生成后重读一次")
    void todayTasksDelegatesGenerationThenRereads() {
        LocalDate today = LocalDate.now();
        when(dailyTaskMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(task(100L, 1, 5L, today)));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(question(5L, "JVM 内存结构")));

        List<Map<String, Object>> out = service.todayTasks(7L);

        assertEquals(1, out.size());
        verify(dailyTaskGenerator).generate(7L, today);
    }

    @Test
    @DisplayName("追加题目用当天最大序号 +1，而不是题目个数")
    void appendToTodayUsesMaxSeq() {
        LocalDate today = LocalDate.now();
        when(dailyTaskMapper.selectCount(any())).thenReturn(0L);
        when(dailyTaskMapper.maxSeq(3L, today)).thenReturn(12);

        service.appendToToday(3L, 99L);

        ArgumentCaptor<DailyTask> captor = ArgumentCaptor.forClass(DailyTask.class);
        verify(dailyTaskMapper).insert(captor.capture());
        assertEquals(13, captor.getValue().getSeq(), "中间删过题时 COUNT 会错位，必须取 MAX(seq)+1");
        assertEquals(99L, captor.getValue().getQuestionId());
    }

    @Test
    @DisplayName("重复追加同一道题直接返回，不做任何写操作")
    void appendToTodaySkipsExisting() {
        when(dailyTaskMapper.selectCount(any())).thenReturn(1L);

        service.appendToToday(3L, 42L);

        verify(dailyTaskMapper, never()).insert(any(DailyTask.class));
        verify(dailyTaskMapper, never()).maxSeq(any(), any());
    }

    @Test
    @DisplayName("仪表盘统计改为 SQL 聚合，不再把作答记录拉进内存计数")
    void statsUsesSqlAggregation() {
        when(practiceRecordMapper.dailyTrend(any())).thenReturn(List.of());
        when(questionMapper.selectCount(any())).thenReturn(112L);
        when(practiceRecordMapper.masterySummary(any()))
                .thenReturn(Map.of("answered", 40L, "mastered", 30L, "weak", 10L));
        when(practiceRecordMapper.categoryProgress(any())).thenReturn(List.of());
        when(dailyTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewStateService.countDue(9L)).thenReturn(7);

        Map<String, Object> s = service.stats(9L);

        assertEquals(40L, s.get("answered"));
        assertEquals(30L, s.get("mastered"));
        assertEquals(10L, s.get("weak"));
        assertEquals(75L, s.get("masteryRate"), "30/40 应为 75%");
        assertEquals(36L, s.get("coverage"), "40/112 应为 36%");
        assertEquals(7, s.get("dueCount"), "今天到期 7 题要如实上报，前端据此提示积压");
        verify(practiceRecordMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("错题本的过滤与排序下沉到 SQL")
    void wrongBookPushesFilterToSql() {
        when(practiceRecordMapper.latestFiltered(1L, 2))
                .thenReturn(List.of(Map.of("questionId", 3L, "mastery", 2, "lastAt", "2026-09-16 10:00:00")));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(question(3L, "Redis 持久化")));

        List<Map<String, Object>> wb = service.wrongBook(1L, 2);

        assertEquals(1, wb.size());
        assertEquals(2, wb.get(0).get("mastery"));
        assertEquals("Redis 持久化", wb.get(0).get("title"));
        verify(practiceRecordMapper).latestFiltered(1L, 2);
    }

    @Test
    @DisplayName("连续打卡天数：今天没刷但昨天刷了也算连续")
    void streakDaysCounting() {
        LocalDate today = LocalDate.now();

        assertEquals(0L, PracticeService.streakDays(List.of()), "没有记录就是 0");
        assertEquals(1L, PracticeService.streakDays(trend(today)), "只刷了今天");
        assertEquals(2L, PracticeService.streakDays(trend(today, today.minusDays(1))), "今天和昨天");
        assertEquals(1L, PracticeService.streakDays(trend(today, today.minusDays(2))), "中间断了一天");
        assertEquals(1L, PracticeService.streakDays(trend(today.minusDays(1))), "今天还没刷，昨天刷了");
        assertEquals(0L, PracticeService.streakDays(trend(today.minusDays(2))), "前天之后就断了");
    }

    private static List<Map<String, Object>> trend(LocalDate... days) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LocalDate d : days) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", d.toString());
            list.add(row);
        }
        return list;
    }

    private static ReviewState reviewState(long questionId, int intervalDays) {
        ReviewState s = new ReviewState();
        s.setQuestionId(questionId);
        s.setIntervalDays(intervalDays);
        s.setNextReviewAt(LocalDate.now().plusDays(intervalDays));
        return s;
    }

    private static DailyTask task(long id, int seq, long questionId, LocalDate date) {
        DailyTask t = new DailyTask();
        t.setId(id);
        t.setUserId(1L);
        t.setTaskDate(date);
        t.setQuestionId(questionId);
        t.setSeq(seq);
        t.setMastery(PracticeService.MASTERY_UNKNOWN);
        return t;
    }

    private static Question question(long id, String title) {
        Question q = new Question();
        q.setId(id);
        q.setCategoryId(1L);
        q.setTitle(title);
        q.setDifficulty(2);
        q.setTags("标签");
        q.setHot(1);
        return q;
    }
}
