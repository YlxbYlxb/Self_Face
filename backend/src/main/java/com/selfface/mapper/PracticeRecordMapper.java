package com.selfface.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.selfface.entity.PracticeRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 作答明细的读取。
 *
 * <p>职责边界：本 mapper 只负责「回看」类查询（错题本、趋势、分类进度），
 * <b>不承担组卷</b>。组卷判断一道题该不该出现，走的是 {@code ReviewStateMapper}
 * 维护的调度状态——那里每题一行，而明细表随刷题量线性增长，不适合放在热路径上。
 */
public interface PracticeRecordMapper extends BaseMapper<PracticeRecord> {

    /**
     * 每道题的最新状态里，按掌握程度过滤（错题本用）。
     * masteryFilter 为 null 时取「不会 / 模糊」，否则精确匹配。
     */
    @Select("""
            <script>
            SELECT r.question_id AS questionId, r.mastery AS mastery, r.created_at AS lastAt
            FROM practice_record r
            JOIN (
                SELECT question_id, MAX(id) AS max_id
                FROM practice_record
                WHERE user_id = #{userId}
                GROUP BY question_id
            ) latest ON latest.max_id = r.id
            <where>
                <choose>
                    <when test="masteryFilter != null">r.mastery = #{masteryFilter}</when>
                    <otherwise>r.mastery &lt;= 2</otherwise>
                </choose>
            </where>
            ORDER BY r.created_at DESC
            </script>
            """)
    List<Map<String, Object>> latestFiltered(@Param("userId") Long userId,
                                             @Param("masteryFilter") Integer masteryFilter);

    /**
     * 总体掌握情况：已答题目数 / 掌握数 / 待复习数。
     * 直接在 SQL 里聚合，避免把用户全部作答行拉进 Java 再 stream 计数。
     */
    @Select("""
            SELECT COUNT(*)                                                       AS answered,
                   COALESCE(SUM(CASE WHEN r.mastery = 3 THEN 1 ELSE 0 END), 0)    AS mastered,
                   COALESCE(SUM(CASE WHEN r.mastery <= 2 THEN 1 ELSE 0 END), 0)   AS weak
            FROM practice_record r
            JOIN (
                SELECT question_id, MAX(id) AS max_id
                FROM practice_record
                WHERE user_id = #{userId}
                GROUP BY question_id
            ) latest ON latest.max_id = r.id
            """)
    Map<String, Object> masterySummary(@Param("userId") Long userId);

    /**
     * 按天聚合的刷题量，用于仪表盘趋势与连续打卡计算。
     */
    @Select("""
            SELECT DATE(created_at) AS day,
                   COUNT(*) AS total,
                   SUM(CASE WHEN mastery = 3 THEN 1 ELSE 0 END) AS mastered
            FROM practice_record
            WHERE user_id = #{userId}
            GROUP BY DATE(created_at)
            ORDER BY day DESC
            LIMIT 60
            """)
    List<Map<String, Object>> dailyTrend(@Param("userId") Long userId);

    /**
     * 各分类的掌握情况，用于雷达/条形图。
     */
    @Select("""
            SELECT c.id AS categoryId,
                   c.name AS categoryName,
                   COUNT(*) AS answered,
                   SUM(CASE WHEN t.mastery = 3 THEN 1 ELSE 0 END) AS mastered
            FROM (
                SELECT question_id, SUBSTRING_INDEX(GROUP_CONCAT(mastery ORDER BY id DESC), ',', 1) AS mastery
                FROM practice_record
                WHERE user_id = #{userId}
                GROUP BY question_id
            ) t
            JOIN question q ON q.id = t.question_id
            JOIN category c ON c.id = q.category_id
            GROUP BY c.id, c.name
            ORDER BY c.sort_order
            """)
    List<Map<String, Object>> categoryProgress(@Param("userId") Long userId);
}
