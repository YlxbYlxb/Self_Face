package com.selfface.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.selfface.entity.Question;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface QuestionMapper extends BaseMapper<Question> {

    /**
     * 各分类的题目数，一次 GROUP BY 取完。
     * 之前是「先查分类列表，再对每个分类单独 count」，11 个分类就是 11 次往返。
     */
    @Select("""
            SELECT category_id AS categoryId, COUNT(*) AS cnt
            FROM question
            GROUP BY category_id
            """)
    List<Map<String, Object>> countGroupByCategory();

    /**
     * 按一个关键词检索题库，用于「JD / 简历 → 相关考点」的召回。
     *
     * <p>匹配面覆盖题干、标签、答案三处。答案是 TEXT，这个 LIKE 会走全表扫描——
     * 题库在千级以内没问题，上万条时应当换成 ES 全文索引或向量检索（召回质量也更好，
     * 因为 LIKE 匹配不到「JVM 调优」与「GC 优化」这类同义表达）。
     */
    @Select("""
            SELECT id, category_id, title, difficulty, tags, hot
            FROM question
            WHERE title  LIKE CONCAT('%', #{keyword}, '%')
               OR tags   LIKE CONCAT('%', #{keyword}, '%')
               OR answer LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY hot DESC, id ASC
            LIMIT #{limit}
            """)
    List<Question> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    /**
     * 批量插入题目。导入题库一次可能几百条，逐条 insert 就是几百次往返。
     * 调用方已在事务中且完成了标题去重，所以这里用普通 INSERT 而非 INSERT IGNORE ——
     * 真出现重复时应当报错回滚，而不是悄悄少插几条让统计对不上。
     */
    @Insert("""
            <script>
            INSERT INTO question
                (category_id, title, answer, difficulty, tags, hot, created_at)
            VALUES
            <foreach collection="list" item="q" separator=",">
                (#{q.categoryId}, #{q.title}, #{q.answer}, #{q.difficulty}, #{q.tags}, #{q.hot}, #{q.createdAt})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("list") List<Question> list);
}
