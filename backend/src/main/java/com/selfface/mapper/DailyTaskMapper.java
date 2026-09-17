package com.selfface.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.selfface.entity.DailyTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface DailyTaskMapper extends BaseMapper<DailyTask> {

    /**
     * 当天题单的最大序号。追加题目时用它 +1。
     * 注意不能用 COUNT(*) 代替：中间若删过题，COUNT 会和真实序号错位，导致新题排到前面。
     */
    @Select("""
            SELECT COALESCE(MAX(seq), 0)
            FROM daily_task
            WHERE user_id = #{userId} AND task_date = #{taskDate}
            """)
    int maxSeq(@Param("userId") Long userId, @Param("taskDate") LocalDate taskDate);

    /**
     * 当天题单里已经有的题目 id。
     * 批量追加前先查一次，用来跳过重复项——这样序号不会因为 INSERT IGNORE
     * 静默跳过而出现空洞。
     */
    @Select("""
            SELECT question_id
            FROM daily_task
            WHERE user_id = #{userId} AND task_date = #{taskDate}
            """)
    List<Long> questionIdsOfDay(@Param("userId") Long userId, @Param("taskDate") LocalDate taskDate);

    /**
     * 批量写入今日题单，靠唯一键 uk_daily_user_date_question 做幂等。
     * 用 INSERT IGNORE 而非逐条 insert：并发生成时不会撞唯一键抛异常，一次往返搞定十行。
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO daily_task
                (user_id, task_date, question_id, seq, mastery, created_at, updated_at)
            VALUES
            <foreach collection="list" item="t" separator=",">
                (#{t.userId}, #{t.taskDate}, #{t.questionId}, #{t.seq}, #{t.mastery}, #{t.createdAt}, #{t.updatedAt})
            </foreach>
            </script>
            """)
    int batchInsertIgnore(@Param("list") List<DailyTask> list);
}
