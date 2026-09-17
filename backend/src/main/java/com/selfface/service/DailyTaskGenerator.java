package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.selfface.common.BizException;
import com.selfface.entity.DailyTask;
import com.selfface.entity.Question;
import com.selfface.mapper.DailyTaskMapper;
import com.selfface.mapper.QuestionMapper;
import com.selfface.mapper.ReviewStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 今日题单的生成器。
 *
 * <p>组卷三步：<b>到期复习题 → 新题补充 → 兜底补足</b>。
 * 「到期」由 {@link Sm2Scheduler} 维护在 review_state 表里，而不是靠「最近一次答得差」
 * 临时挑——后者有两个毛病：答对的题从此永久消失（面试前早忘了，系统却认为你会），
 * 答错的题每天重复出现（是「刷到会为止」，不是按遗忘曲线复习）。
 *
 * <p>单独抽成一个 bean 还有两个原因：
 * <ol>
 *   <li>事务边界要落在「组卷 + 落库」这一小段上，而不是整个读取流程；</li>
 *   <li>读取端（PracticeService）必须运行在事务之外，这样生成结束后再查一次拿到的是新快照，
 *       才能看见并发请求刚刚提交的行——否则在 REPEATABLE READ 下会一直读到空。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyTaskGenerator {

    /** 每日题单容量 */
    static final int DAILY_SIZE = 10;
    /** 题单里到期复习题的数量上限，其余名额留给新题 */
    static final int REVIEW_QUOTA = 5;

    private final DailyTaskMapper dailyTaskMapper;
    private final QuestionMapper questionMapper;
    private final ReviewStateMapper reviewStateMapper;

    /**
     * 为一个用户生成某天的题单并落库。重复调用安全（唯一键 + INSERT IGNORE）。
     */
    @Transactional
    public void generate(Long userId, LocalDate today) {
        List<Long> picked = pickQuestions(userId, today);
        if (picked.isEmpty()) {
            throw new BizException("题库还是空的，请先导入题目数据");
        }
        LocalDateTime now = LocalDateTime.now();
        List<DailyTask> batch = new ArrayList<>(picked.size());
        int seq = 1;
        for (Long qid : picked) {
            DailyTask t = new DailyTask();
            t.setUserId(userId);
            t.setTaskDate(today);
            t.setQuestionId(qid);
            t.setSeq(seq++);
            t.setMastery(PracticeService.MASTERY_UNKNOWN);
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            batch.add(t);
        }
        int inserted = dailyTaskMapper.batchInsertIgnore(batch);
        log.info("用户 {} 生成 {} 日题单，候选 {} 题，实际新增 {} 题", userId, today, batch.size(), inserted);
    }

    /**
     * 组卷三步：到期复习 → 新题 → 兜底。
     */
    private List<Long> pickQuestions(Long userId, LocalDate today) {
        Set<Long> chosen = new LinkedHashSet<>();

        // 1. 到期复习题：SM-2 算出「今天该复习」的题。
        //    排序在 SQL 里按 next_review_at → ease_factor → lapse_count，
        //    即「越早该复习的、越难记的、忘过越多次的」越靠前。
        int dueQuota = Math.min(REVIEW_QUOTA, DAILY_SIZE);
        reviewStateMapper.dueQuestionIds(userId, today, dueQuota).forEach(chosen::add);

        // 2. 新题：从来没有复习状态的题里，高频考点优先
        if (chosen.size() < DAILY_SIZE) {
            QueryWrapper<Question> qw = new QueryWrapper<>();
            qw.select("id", "category_id");
            // 有 review_state 行就代表做过，天然排除了已掌握和待复习的题
            qw.apply("NOT EXISTS (SELECT 1 FROM review_state rs "
                    + "WHERE rs.user_id = {0} AND rs.question_id = question.id)", userId);
            qw.orderByDesc("hot").orderByAsc("id").last("LIMIT " + (DAILY_SIZE * 3));
            fillBalanced(chosen, questionMapper.selectList(qw), DAILY_SIZE);
        }

        // 3. 兜底：题库做完了、且今天没有到期题时，挑「最久没碰过」的保持手感。
        //    取代原先按 id 顺序轮询——轮询会出现「昨天刚复习、今天又出现」的观感问题。
        if (chosen.size() < DAILY_SIZE) {
            fillBalanced(chosen,
                    reviewStateMapper.stalestQuestions(userId, chosen, DAILY_SIZE * 3),
                    DAILY_SIZE);
        }
        return new ArrayList<>(chosen);
    }

    /**
     * 按分类轮流取，避免一天十道全是 MySQL。
     */
    private void fillBalanced(Set<Long> chosen, List<Question> pool, int target) {
        Map<Long, List<Question>> byCategory = new LinkedHashMap<>();
        for (Question q : pool) {
            if (chosen.contains(q.getId())) {
                continue;
            }
            byCategory.computeIfAbsent(q.getCategoryId(), k -> new ArrayList<>()).add(q);
        }
        boolean progressed = true;
        while (chosen.size() < target && progressed) {
            progressed = false;
            for (List<Question> list : byCategory.values()) {
                if (chosen.size() >= target) {
                    break;
                }
                while (!list.isEmpty() && chosen.contains(list.get(0).getId())) {
                    list.remove(0);
                }
                if (!list.isEmpty()) {
                    chosen.add(list.remove(0).getId());
                    progressed = true;
                }
            }
        }
    }
}
