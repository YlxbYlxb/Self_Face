package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一道题的复习调度状态（SM-2）。
 *
 * <p>与 {@link PracticeRecord} 的分工：后者是「明细」，每次作答追加一条、只增不改，用于回看表述；
 * 本类是「聚合」，每道题一行、被反复覆盖，只关心「什么时候该再复习」。
 * 把调度状态独立出来还有个好处——组卷时只需扫这张小表，不必去聚合海量作答明细。
 */
@Data
@TableName("review_state")
public class ReviewState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long questionId;

    /** 最近一次作答时间 */
    private LocalDateTime lastReviewedAt;

    /** 到期日：<= 今天就会进入复习队列 */
    private LocalDate nextReviewAt;

    /** 当前复习间隔（天） */
    private Integer intervalDays;

    /** SM-2 难度系数，1.30~2.80。越大说明这题对你越简单，间隔涨得越快 */
    private BigDecimal easeFactor;

    private Integer reviewCount;

    /** 答「不会」的累计次数，越高的越优先安排复习 */
    private Integer lapseCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
