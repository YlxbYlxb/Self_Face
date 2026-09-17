package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.selfface.common.BizException;
import com.selfface.entity.DailyTask;
import com.selfface.entity.PracticeRecord;
import com.selfface.entity.Question;
import com.selfface.entity.ReviewState;
import com.selfface.mapper.DailyTaskMapper;
import com.selfface.mapper.PracticeRecordMapper;
import com.selfface.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeService {

    public static final int MASTERY_UNKNOWN = 0;
    public static final int MASTERY_FAIL = 1;
    public static final int MASTERY_VAGUE = 2;
    public static final int MASTERY_DONE = 3;

    /** 一次批量追加的题目上限，防止前端误传超大数组打满数据库 */
    static final int MAX_BATCH_APPEND = 50;

    private final DailyTaskMapper dailyTaskMapper;
    private final PracticeRecordMapper practiceRecordMapper;
    private final QuestionMapper questionMapper;
    private final DailyTaskGenerator dailyTaskGenerator;
    private final ReviewStateService reviewStateService;

    /**
     * 取今日题单。不存在则即时组卷并落库，重复调用返回同一份。
     *
     * <p>本方法刻意不加 {@code @Transactional}：先读一次，若为空再交给生成器写库，
     * 写完后必须在新快照里重读才能看见并发请求提交的结果。
     */
    public List<Map<String, Object>> todayTasks(Long userId) {
        LocalDate today = LocalDate.now();
        List<DailyTask> tasks = readToday(userId, today);
        if (tasks.isEmpty()) {
            dailyTaskGenerator.generate(userId, today);
            tasks = readToday(userId, today);
        }
        if (tasks.isEmpty()) {
            throw new BizException("今日题单正在生成，请刷新重试");
        }
        return decorate(tasks, userId);
    }

    private List<DailyTask> readToday(Long userId, LocalDate today) {
        return dailyTaskMapper.selectList(new LambdaQueryWrapper<DailyTask>()
                .eq(DailyTask::getUserId, userId)
                .eq(DailyTask::getTaskDate, today)
                .orderByAsc(DailyTask::getSeq));
    }

    private List<Map<String, Object>> decorate(List<DailyTask> tasks, Long userId) {
        List<Long> ids = tasks.stream().map(DailyTask::getQuestionId).toList();
        Map<Long, Question> questionMap = ids.isEmpty() ? Map.of()
                : questionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        // 只查这十几道题的调度状态，不再全量拉取该用户答过的所有题。
        // 用 review_state 而不是 practice_record 判断：前者一次点查就能顺带拿到
        // 「间隔几天 / 下次哪天复习」，前端不用再补一次请求。
        Map<Long, ReviewState> states = ids.isEmpty() ? Map.of()
                : reviewStateService.statesOf(userId, ids);

        List<Map<String, Object>> result = new ArrayList<>();
        for (DailyTask t : tasks) {
            Question q = questionMap.get(t.getQuestionId());
            if (q == null) {
                continue;
            }
            ReviewState st = states.get(t.getQuestionId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", t.getId());
            item.put("seq", t.getSeq());
            item.put("mastery", t.getMastery());
            // 有调度状态 = 做过这道题（复习题）；没有 = 第一次见（新题）
            item.put("isReview", st != null);
            item.put("isNew", st == null);
            if (st != null) {
                item.put("intervalDays", st.getIntervalDays());
                item.put("nextReviewAt", st.getNextReviewAt() == null
                        ? null : st.getNextReviewAt().toString());
            }
            item.put("questionId", q.getId());
            item.put("categoryId", q.getCategoryId());
            item.put("title", q.getTitle());
            item.put("difficulty", q.getDifficulty());
            item.put("tags", q.getTags());
            item.put("hot", q.getHot());
            result.add(item);
        }
        return result;
    }

    /**
     * 提交一道题的作答结果。同时写 daily_task 状态与 practice_record 明细。
     */
    @Transactional
    public Map<String, Object> submit(Long userId, Long questionId, Integer mastery,
                                      String answerText, Integer costSeconds) {
        if (mastery == null || mastery < MASTERY_FAIL || mastery > MASTERY_DONE) {
            throw new BizException("掌握程度取值范围是 1（不会）、2（模糊）、3（掌握）");
        }
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("题目不存在");
        }

        DailyTask task = dailyTaskMapper.selectOne(new LambdaQueryWrapper<DailyTask>()
                .eq(DailyTask::getUserId, userId)
                .eq(DailyTask::getQuestionId, questionId)
                .eq(DailyTask::getTaskDate, LocalDate.now()));
        if (task != null) {
            task.setMastery(mastery);
            task.setUpdatedAt(LocalDateTime.now());
            dailyTaskMapper.updateById(task);
        }

        PracticeRecord record = new PracticeRecord();
        record.setUserId(userId);
        record.setQuestionId(questionId);
        record.setMastery(mastery);
        record.setAnswerText(answerText);
        record.setCostSeconds(costSeconds);
        record.setCreatedAt(LocalDateTime.now());
        practiceRecordMapper.insert(record);

        // 推进 SM-2 复习计划：这一步决定这道题下次什么时候再出现。
        // 「不会」→ 明天再见；「掌握」→ 按难度系数往后推（1 → 3 → 8 → 22 → 62 天）。
        ReviewState state = reviewStateService.advance(userId, questionId, mastery);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", record.getId());
        result.put("mastery", mastery);
        result.put("answer", q.getAnswer());
        result.put("intervalDays", state.getIntervalDays());
        result.put("nextReviewAt", state.getNextReviewAt() == null
                ? null : state.getNextReviewAt().toString());
        result.put("todayProgress", todayProgress(userId));
        return result;
    }

    public Map<String, Object> todayProgress(Long userId) {
        LocalDate today = LocalDate.now();
        List<DailyTask> tasks = readToday(userId, today);
        long done = tasks.stream().filter(t -> t.getMastery() != null && t.getMastery() > MASTERY_UNKNOWN).count();
        long mastered = tasks.stream().filter(t -> t.getMastery() != null && t.getMastery() == MASTERY_DONE).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", tasks.size());
        m.put("done", done);
        m.put("mastered", mastered);
        m.put("percent", tasks.isEmpty() ? 0 : Math.round(done * 100.0 / tasks.size()));
        return m;
    }

    /**
     * 仪表盘数据：连续打卡、总体掌握率、分类分布、近 14 天趋势。
     */
    public Map<String, Object> stats(Long userId) {
        List<Map<String, Object>> trendRaw = practiceRecordMapper.dailyTrend(userId);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map<String, Object> row : trendRaw) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", toDay(row.get("day")));
            item.put("total", asInt(row.get("total")));
            item.put("mastered", asInt(row.get("mastered")));
            trend.add(item);
        }

        Long totalQuestions = questionMapper.selectCount(null);
        // 三个计数在 SQL 里聚合，不再把用户全部作答行拉进内存再 stream 统计
        Map<String, Object> summary = practiceRecordMapper.masterySummary(userId);
        long answered = asInt(summary == null ? null : summary.get("answered"));
        long mastered = asInt(summary == null ? null : summary.get("mastered"));
        long weak = asInt(summary == null ? null : summary.get("weak"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bankTotal", totalQuestions == null ? 0 : totalQuestions);
        result.put("answered", answered);
        result.put("mastered", mastered);
        result.put("weak", weak);
        result.put("masteryRate", answered == 0 ? 0 : Math.round(mastered * 100.0 / answered));
        result.put("coverage", totalQuestions == null || totalQuestions == 0
                ? 0 : Math.round(answered * 100.0 / totalQuestions));
        result.put("streakDays", streakDays(trend));
        // SM-2 意义上今天到期的题量。它可能多于当日题单容量——差额就是「积压」，
        // 前端据此提示用户「还有 N 题到期没复习」，而不是默默丢掉。
        result.put("dueCount", reviewStateService.countDue(userId));
        result.put("totalRecords", practiceRecordMapper.selectCount(
                new LambdaQueryWrapper<PracticeRecord>().eq(PracticeRecord::getUserId, userId)));
        result.put("today", todayProgress(userId));
        result.put("trend", trend);
        result.put("categories", practiceRecordMapper.categoryProgress(userId));
        return result;
    }

    /**
     * 连续打卡天数。今天还没刷但昨天刷了，也算连续（当天仍有机会补上）。
     * 纯函数，无副作用，可单测。
     */
    static long streakDays(List<Map<String, Object>> trendDesc) {
        if (trendDesc.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate cursor = today;
        LocalDate first = LocalDate.parse((String) trendDesc.get(0).get("day"));
        if (first.equals(today.minusDays(1))) {
            cursor = today.minusDays(1);
        } else if (!first.equals(today)) {
            return 0;
        }
        long streak = 0;
        for (Map<String, Object> row : trendDesc) {
            LocalDate d = LocalDate.parse((String) row.get("day"));
            if (d.equals(cursor)) {
                streak++;
                cursor = cursor.minusDays(1);
            } else if (d.isBefore(cursor)) {
                break;
            }
        }
        return streak;
    }

    /**
     * 错题本：每道题取最近一次作答，掌握状态为「不会」或「模糊」的留下。
     * 过滤与排序都下沉到 SQL，避免把用户全部作答记录拉回应用层再筛。
     */
    public List<Map<String, Object>> wrongBook(Long userId, Integer masteryFilter) {
        List<Map<String, Object>> target = practiceRecordMapper.latestFiltered(userId, masteryFilter);
        if (target.isEmpty()) {
            return List.of();
        }
        List<Long> ids = target.stream().map(m -> asLong(m.get("questionId"))).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : target) {
            Question q = questionMap.get(asLong(m.get("questionId")));
            if (q == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", q.getId());
            item.put("title", q.getTitle());
            item.put("answer", q.getAnswer());
            item.put("difficulty", q.getDifficulty());
            item.put("tags", q.getTags());
            item.put("mastery", asInt(m.get("mastery")));
            item.put("lastAt", m.get("lastAt"));
            result.add(item);
        }
        return result;
    }

    /**
     * 某道题的历史作答记录，用来回看自己的表述有没有长进。
     */
    public List<PracticeRecord> history(Long userId, Long questionId) {
        return practiceRecordMapper.selectList(new LambdaQueryWrapper<PracticeRecord>()
                .eq(PracticeRecord::getUserId, userId)
                .eq(PracticeRecord::getQuestionId, questionId)
                .orderByDesc(PracticeRecord::getId)
                .last("LIMIT 20"));
    }

    /**
     * 手动追加题目到今日题单，用于「这道题我还想再练一遍」。
     */
    @Transactional
    public void appendToToday(Long userId, Long questionId) {
        LocalDate today = LocalDate.now();
        Long exists = dailyTaskMapper.selectCount(new LambdaQueryWrapper<DailyTask>()
                .eq(DailyTask::getUserId, userId)
                .eq(DailyTask::getTaskDate, today)
                .eq(DailyTask::getQuestionId, questionId));
        if (exists != null && exists > 0) {
            return;
        }
        // 序号取当天 MAX(seq) + 1：用 COUNT(*) 会在题目被删过之后错位
        int seq = dailyTaskMapper.maxSeq(userId, today) + 1;
        DailyTask t = new DailyTask();
        t.setUserId(userId);
        t.setTaskDate(today);
        t.setQuestionId(questionId);
        t.setSeq(seq);
        t.setMastery(MASTERY_UNKNOWN);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        dailyTaskMapper.insert(t);
    }

    /**
     * 批量追加题目到今日题单（JD 定向题单一键导入用）。
     *
     * <p>与单条追加一样是幂等的：已经在题单里的题会被跳过，返回实际新增条数。
     * 这里先查一次当天已有的 id 再过滤，而不是只靠 INSERT IGNORE 兜底 ——
     * 后者虽然也不会插重，但 seq 是按循环下标赋的，重复项会让序号留下空洞。
     */
    @Transactional
    public int appendBatchToToday(Long userId, List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            throw new BizException("请先选择要加入题单的题目");
        }
        if (questionIds.size() > MAX_BATCH_APPEND) {
            throw new BizException("一次最多加入 " + MAX_BATCH_APPEND + " 道题");
        }

        LocalDate today = LocalDate.now();
        Set<Long> existing = new HashSet<>(dailyTaskMapper.questionIdsOfDay(userId, today));
        List<Long> candidates = questionIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(id -> !existing.contains(id))
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }

        // 过滤掉题库里已被删掉的 id，避免往题单写孤儿行
        Set<Long> valid = questionMapper.selectBatchIds(candidates).stream()
                .map(Question::getId)
                .collect(Collectors.toSet());

        int seq = dailyTaskMapper.maxSeq(userId, today);
        LocalDateTime now = LocalDateTime.now();
        List<DailyTask> batch = new ArrayList<>();
        for (Long qid : candidates) {
            if (!valid.contains(qid)) {
                continue;
            }
            DailyTask t = new DailyTask();
            t.setUserId(userId);
            t.setTaskDate(today);
            t.setQuestionId(qid);
            t.setSeq(++seq);
            t.setMastery(MASTERY_UNKNOWN);
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            batch.add(t);
        }
        if (batch.isEmpty()) {
            return 0;
        }
        int inserted = dailyTaskMapper.batchInsertIgnore(batch);
        log.info("用户 {} 批量追加 {} 道题到 {} 日题单", userId, inserted, today);
        return inserted;
    }

    static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    static String toDay(Object o) {
        if (o == null) {
            return "";
        }
        String s = o.toString();
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }
}
