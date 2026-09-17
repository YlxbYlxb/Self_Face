package com.selfface.service;

import com.selfface.common.BizException;
import com.selfface.entity.ReviewState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SM-2 调度的核心行为。
 *
 * <p>这些用例的价值在于把「复习间隔到底怎么变」钉死成可验证的数字——
 * 算法出错不会报错、只会静默地让复习计划变得没意义，所以必须靠测试兜住。
 */
class Sm2SchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @Test
    @DisplayName("连续答「掌握」时，间隔按 1 → 3 → 8 → 22 → 62 天拉长")
    void intervalsGrowOnRepeatedSuccess() {
        List<Integer> intervals = new ArrayList<>();
        ReviewState current = null;

        for (int i = 0; i < 5; i++) {
            Sm2Scheduler.Plan plan = Sm2Scheduler.next(current, PracticeService.MASTERY_DONE, TODAY);
            intervals.add(plan.intervalDays());
            current = stateOf(plan);
        }

        assertEquals(List.of(1, 3, 8, 22, 62), intervals,
                "前两次用固定值（1、3 天），之后才按难度系数放大");
    }

    @Test
    @DisplayName("答「掌握」会抬高难度系数，下次间隔涨得更快")
    void easeFactorGrowsOnSuccess() {
        Sm2Scheduler.Plan first = Sm2Scheduler.next(null, PracticeService.MASTERY_DONE, TODAY);
        assertEquals(2.60, first.easeFactor(), 0.001, "初始 2.50，答对一次 +0.10");

        Sm2Scheduler.Plan second = Sm2Scheduler.next(stateOf(first), PracticeService.MASTERY_DONE, TODAY);
        assertEquals(2.70, second.easeFactor(), 0.001);
    }

    @Test
    @DisplayName("答「不会」时打回 1 天重来，难度系数下调，遗忘次数 +1")
    void failureResetsIntervalAndDropsEase() {
        ReviewState practiced = state(22, 2.80, 5, 1);

        Sm2Scheduler.Plan plan = Sm2Scheduler.next(practiced, PracticeService.MASTERY_FAIL, TODAY);

        assertEquals(1, plan.intervalDays(), "忘了就回到起点，明天再见");
        assertEquals(2.60, plan.easeFactor(), 0.001, "难度系数 -0.20");
        assertEquals(6, plan.reviewCount());
        assertEquals(2, plan.lapseCount(), "遗忘次数要累加，组卷时据此优先安排");
    }

    @Test
    @DisplayName("答「模糊」是小幅前进，不按难度系数放大")
    void vagueGrowsSlowly() {
        Sm2Scheduler.Plan plan = Sm2Scheduler.next(state(3, 2.50, 2, 0),
                PracticeService.MASTERY_VAGUE, TODAY);

        assertEquals(4, plan.intervalDays(), "3 × 1.2 = 3.6，四舍五入到 4");
        assertEquals(2.35, plan.easeFactor(), 0.001, "难度系数 -0.15");
    }

    @Test
    @DisplayName("首次作答答「模糊」也不会给出 0 天")
    void vagueOnFirstAttemptStillSchedulesTomorrow() {
        Sm2Scheduler.Plan plan = Sm2Scheduler.next(null, PracticeService.MASTERY_VAGUE, TODAY);
        assertEquals(1, plan.intervalDays());
    }

    @Test
    @DisplayName("难度系数不会低于下限 1.30")
    void easeFactorNeverDropsBelowFloor() {
        ReviewState current = null;
        for (int i = 0; i < 10; i++) {
            Sm2Scheduler.Plan plan = Sm2Scheduler.next(current, PracticeService.MASTERY_FAIL, TODAY);
            current = stateOf(plan);
            assertTrue(plan.easeFactor() >= Sm2Scheduler.EF_MIN,
                    "第 " + (i + 1) + " 次后难度系数跌破了下限：" + plan.easeFactor());
        }
        assertEquals(1.30, current.getEaseFactor().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("难度系数不会高于上限 2.80")
    void easeFactorNeverExceedsCeiling() {
        ReviewState current = null;
        for (int i = 0; i < 10; i++) {
            Sm2Scheduler.Plan plan = Sm2Scheduler.next(current, PracticeService.MASTERY_DONE, TODAY);
            current = stateOf(plan);
            assertTrue(plan.easeFactor() <= Sm2Scheduler.EF_MAX,
                    "第 " + (i + 1) + " 次后难度系数超过了上限：" + plan.easeFactor());
        }
        assertEquals(2.80, current.getEaseFactor().doubleValue(), 0.001);
    }

    @Test
    @DisplayName("到期日 = 今天 + 间隔天数")
    void nextReviewDateIsTodayPlusInterval() {
        Sm2Scheduler.Plan plan = Sm2Scheduler.next(null, PracticeService.MASTERY_DONE, TODAY);
        assertEquals(TODAY.plusDays(1), plan.nextReviewAt());

        Sm2Scheduler.Plan fail = Sm2Scheduler.next(null, PracticeService.MASTERY_FAIL, TODAY);
        assertEquals(TODAY.plusDays(1), fail.nextReviewAt());
    }

    @Test
    @DisplayName("非法的掌握程度直接报错，不静默改成默认值")
    void rejectsInvalidMastery() {
        assertThrows(BizException.class, () -> Sm2Scheduler.next(null, 0, TODAY));
        assertThrows(BizException.class, () -> Sm2Scheduler.next(null, 4, TODAY));
    }

    @Test
    @DisplayName("落库前难度系数统一保留两位小数")
    void easeFactorIsScaledForStorage() {
        assertEquals(new BigDecimal("2.35"), Sm2Scheduler.toScale(2.35));
        assertEquals(new BigDecimal("2.50"), Sm2Scheduler.toScale(2.5));
    }

    private static ReviewState stateOf(Sm2Scheduler.Plan plan) {
        return state(plan.intervalDays(), plan.easeFactor(), plan.reviewCount(), plan.lapseCount());
    }

    private static ReviewState state(int intervalDays, double easeFactor, int reviewCount, int lapseCount) {
        ReviewState s = new ReviewState();
        s.setIntervalDays(intervalDays);
        s.setEaseFactor(BigDecimal.valueOf(easeFactor));
        s.setReviewCount(reviewCount);
        s.setLapseCount(lapseCount);
        return s;
    }
}
