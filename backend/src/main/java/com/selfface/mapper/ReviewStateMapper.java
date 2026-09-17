package com.selfface.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.selfface.entity.Question;
import com.selfface.entity.ReviewState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ReviewStateMapper extends BaseMapper<ReviewState> {

    /**
     * 今天该复习的题。
     *
     * <p>排序规则对应三件事：<b>越早该复习的越急</b>（next_review_at）、
     * 同样到期时<b>越难记的越优先</b>（ease_factor 小的说明这题对你偏难）、
     * 最后参考<b>忘过几次</b>（lapse_count 大的更容易再忘）。
     */
    @Select("""
            SELECT rs.question_id
            FROM review_state rs
            JOIN question q ON q.id = rs.question_id
            WHERE rs.user_id = #{userId}
              AND rs.next_review_at <= #{today}
            ORDER BY rs.next_review_at ASC, rs.ease_factor ASC, rs.lapse_count DESC
            LIMIT #{limit}
            """)
    List<Long> dueQuestionIds(@Param("userId") Long userId,
                              @Param("today") LocalDate today,
                              @Param("limit") int limit);

    /**
     * 兜底用：题库全做完且没有到期题时，挑「离下次复习最远 / 最久没碰过」的补足。
     * 取代原先按 id 顺序轮询——轮询会出现「刚复习完的第二天又出现」这种观感问题。
     */
    @Select("""
            <script>
            SELECT q.id AS id, q.category_id AS categoryId
            FROM review_state rs
            JOIN question q ON q.id = rs.question_id
            WHERE rs.user_id = #{userId}
            <if test="exclude != null and exclude.size() > 0">
                AND rs.question_id NOT IN
                <foreach collection="exclude" item="qid" open="(" separator="," close=")">#{qid}</foreach>
            </if>
            ORDER BY rs.next_review_at ASC, rs.last_reviewed_at ASC
            LIMIT #{limit}
            </script>
            """)
    List<Question> stalestQuestions(@Param("userId") Long userId,
                                    @Param("exclude") Collection<Long> exclude,
                                    @Param("limit") int limit);

    /**
     * 取单道题的调度状态。
     * 用显式列名的注解 SQL 而不是 LambdaQueryWrapper：后者需要 MyBatis-Plus 的
     * TableInfo 元数据，在纯 mock 单测里没初始化会直接 NPE。
     */
    @Select("""
            SELECT * FROM review_state
            WHERE user_id = #{userId} AND question_id = #{questionId}
            """)
    ReviewState findByUserAndQuestion(@Param("userId") Long userId,
                                      @Param("questionId") Long questionId);

    /**
     * 批量取状态，用于给今日题单标注「复习题 / 下次复习时间」。
     */
    @Select("""
            <script>
            SELECT * FROM review_state
            WHERE user_id = #{userId} AND question_id IN
            <foreach collection="questionIds" item="qid" open="(" separator="," close=")">#{qid}</foreach>
            </script>
            """)
    List<ReviewState> ofQuestions(@Param("userId") Long userId,
                                 @Param("questionIds") Collection<Long> questionIds);

    /**
     * 幂等插入：并发提交同一道题时不会撞唯一键抛异常。
     */
    @Insert("""
            INSERT IGNORE INTO review_state
                (user_id, question_id, last_reviewed_at, next_review_at,
                 interval_days, ease_factor, review_count, lapse_count, created_at, updated_at)
            VALUES
                (#{userId}, #{questionId}, #{lastReviewedAt}, #{nextReviewAt},
                 #{intervalDays}, #{easeFactor}, #{reviewCount}, #{lapseCount}, #{createdAt}, #{updatedAt})
            """)
    int insertIgnore(ReviewState state);

    /**
     * 今天到期的题量，用于仪表盘提示「今天有 N 题到期」。
     * 注意这里的「到期」是 SM-2 意义上该复习的，与当日题单容量不是一回事。
     */
    @Select("""
            SELECT COUNT(*)
            FROM review_state
            WHERE user_id = #{userId} AND next_review_at <= #{today}
            """)
    int countDue(@Param("userId") Long userId, @Param("today") LocalDate today);
}
