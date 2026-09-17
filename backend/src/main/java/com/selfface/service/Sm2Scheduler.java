package com.selfface.service;

import com.selfface.common.BizException;
import com.selfface.entity.ReviewState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * SM-2 间隔重复调度器（Piotr Woźniak 的 SuperMemo 2 简化版）。
 *
 * <p>核心思想：每次复习后，把「下次再说」的时间往后推，推的幅度取决于两点——
 * 你这次答得怎么样（自评），以及这道题对你来说有多难（ease factor）。
 * 答得好的题间隔指数增长（1 → 3 → 8 → 22 → 62 天），答不好的题被打回 1 天重来。
 *
 * <p>相比「错题优先」的朴素策略，它解决的是两件事：
 * <ol>
 *   <li>答对的题不会永久消失——它们会按曲线在快忘记的时候重新出现；</li>
 *   <li>错题不会每天重复轰炸——但也不会被放过，间隔从 1 天开始重新爬。</li>
 * </ol>
 *
 * <p>纯静态计算，不碰数据库，可以放心单测。
 */
public final class Sm2Scheduler {

    /** 新题的初始难度系数 */
    public static final double EF_INITIAL = 2.50;
    /** 难度系数下限：再难的题也不能低于这个值，否则间隔涨不起来 */
    public static final double EF_MIN = 1.30;
    /** 难度系数上限 */
    public static final double EF_MAX = 2.80;

    /** 自评「模糊」时的间隔增长系数（不按 EF 走，步子小一点） */
    private static final double VAGUE_GROWTH = 1.2;

    /** 自评「掌握」时的 EF 增量 */
    private static final double EF_GAIN = 0.10;
    /** 自评「模糊」时的 EF 减量 */
    private static final double EF_LOSS_VAGUE = 0.15;
    /** 自评「不会」时的 EF 减量 */
    private static final double EF_LOSS_FAIL = 0.20;

    /** 一次复习后的新计划 */
    public record Plan(int intervalDays, double easeFactor, LocalDate nextReviewAt,
                       int reviewCount, int lapseCount) {
    }

    private Sm2Scheduler() {
    }

    /**
     * 根据本次自评推进复习计划。
     *
     * @param current 该题当前状态，首次作答传 {@code null}
     * @param mastery 1 不会 / 2 模糊 / 3 掌握
     * @param today   作答日期
     */
    public static Plan next(ReviewState current, int mastery, LocalDate today) {
        double ef = current == null || current.getEaseFactor() == null
                ? EF_INITIAL : current.getEaseFactor().doubleValue();
        int interval = current == null || current.getIntervalDays() == null
                ? 0 : current.getIntervalDays();
        int reviewCount = current == null || current.getReviewCount() == null
                ? 0 : current.getReviewCount();
        int lapseCount = current == null || current.getLapseCount() == null
                ? 0 : current.getLapseCount();

        if (mastery == PracticeService.MASTERY_FAIL) {
            // 忘了就打回起点，并把难度系数调低（下次涨得慢一些）
            interval = 1;
            ef = clamp(ef - EF_LOSS_FAIL);
            lapseCount++;
        } else if (mastery == PracticeService.MASTERY_VAGUE) {
            // 模糊：小幅前进，不按 EF 放大
            interval = Math.max(1, (int) Math.round(Math.max(interval, 1) * VAGUE_GROWTH));
            ef = clamp(ef - EF_LOSS_VAGUE);
        } else if (mastery == PracticeService.MASTERY_DONE) {
            // 掌握：间隔按当前 EF 放大；EF 是在算完间隔之后才 +0.1（与标准 SM-2 一致）
            interval = intervalOnSuccess(interval, ef);
            ef = clamp(ef + EF_GAIN);
        } else {
            throw new BizException("掌握程度取值范围是 1（不会）、2（模糊）、3（掌握）");
        }

        reviewCount++;
        return new Plan(interval, ef, today.plusDays(interval), reviewCount, lapseCount);
    }

    /**
     * 答对时的下一个间隔。
     *
     * <p>前两次刻意用固定值（1 天、3 天），而不是直接乘 EF——
     * 新题刚记住时记忆还很脆，立刻跳到 6 天以上容易第二天就忘。
     */
    private static int intervalOnSuccess(int interval, double ef) {
        if (interval <= 0) {
            return 1;
        }
        if (interval == 1) {
            return 3;
        }
        return Math.max(1, (int) Math.round(interval * ef));
    }

    private static double clamp(double ef) {
        return Math.min(EF_MAX, Math.max(EF_MIN, ef));
    }

    /** 写入数据库前统一保留两位小数，避免 DECIMAL(4,2) 的精度噪声 */
    public static BigDecimal toScale(double ef) {
        return BigDecimal.valueOf(ef).setScale(2, RoundingMode.HALF_UP);
    }
}
