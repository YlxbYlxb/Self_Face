package com.selfface.service;

import com.selfface.entity.ReviewState;
import com.selfface.mapper.ReviewStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 复习状态读写。算法本身在 {@link Sm2Scheduler}，这里只负责落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewStateService {

    private final ReviewStateMapper reviewStateMapper;

    /**
     * 一次作答后推进这道题的复习计划。
     *
     * <p>首次作答走 INSERT，之后走 UPDATE。插入用 INSERT IGNORE：
     * 万一同一道题被并发提交两次，不会因为唯一键冲突把整个提交事务带崩，
     * 让后到的那个请求退化成回读即可。
     */
    @Transactional
    public ReviewState advance(Long userId, Long questionId, int mastery) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        ReviewState current = reviewStateMapper.findByUserAndQuestion(userId, questionId);
        Sm2Scheduler.Plan plan = Sm2Scheduler.next(current, mastery, today);

        if (current == null) {
            ReviewState fresh = new ReviewState();
            fresh.setUserId(userId);
            fresh.setQuestionId(questionId);
            fresh.setLastReviewedAt(now);
            fresh.setNextReviewAt(plan.nextReviewAt());
            fresh.setIntervalDays(plan.intervalDays());
            fresh.setEaseFactor(Sm2Scheduler.toScale(plan.easeFactor()));
            fresh.setReviewCount(plan.reviewCount());
            fresh.setLapseCount(plan.lapseCount());
            fresh.setCreatedAt(now);
            fresh.setUpdatedAt(now);
            int inserted = reviewStateMapper.insertIgnore(fresh);
            if (inserted == 0) {
                // 并发下另一个请求刚插进去了，以库里的为准，本次不再覆盖
                log.debug("回顾状态已存在，跳过本次写入：user={} question={}", userId, questionId);
                return reviewStateMapper.findByUserAndQuestion(userId, questionId);
            }
            return fresh;
        }

        current.setLastReviewedAt(now);
        current.setNextReviewAt(plan.nextReviewAt());
        current.setIntervalDays(plan.intervalDays());
        current.setEaseFactor(Sm2Scheduler.toScale(plan.easeFactor()));
        current.setReviewCount(plan.reviewCount());
        current.setLapseCount(plan.lapseCount());
        current.setUpdatedAt(now);
        reviewStateMapper.updateById(current);
        return current;
    }

    public ReviewState find(Long userId, Long questionId) {
        return reviewStateMapper.findByUserAndQuestion(userId, questionId);
    }

    /** 批量取状态（今日题单标注用） */
    public Map<Long, ReviewState> statesOf(Long userId, Collection<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return Map.of();
        }
        List<ReviewState> list = reviewStateMapper.ofQuestions(userId, questionIds);
        return list.stream().collect(Collectors.toMap(
                ReviewState::getQuestionId, Function.identity(), (a, b) -> a));
    }

    /** 今天到期的题量 */
    public int countDue(Long userId) {
        return reviewStateMapper.countDue(userId, LocalDate.now());
    }
}
