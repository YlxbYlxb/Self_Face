package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.selfface.common.BizException;
import com.selfface.entity.Category;
import com.selfface.entity.Question;
import com.selfface.mapper.CategoryMapper;
import com.selfface.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionService {

    private final QuestionMapper questionMapper;
    private final CategoryMapper categoryMapper;

    public List<Map<String, Object>> categoriesWithCount() {
        List<Category> categories = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getSortOrder));
        // 一次 GROUP BY 取回全部分类的题数，替掉「每个分类一次 COUNT」的 N+1
        Map<Long, Integer> countByCategory = questionMapper.countGroupByCategory().stream()
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("categoryId")).longValue(),
                        m -> ((Number) m.get("cnt")).intValue()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Category c : categories) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("code", c.getCode());
            item.put("description", c.getDescription());
            item.put("count", countByCategory.getOrDefault(c.getId(), 0));
            result.add(item);
        }
        return result;
    }

    /**
     * @param withAnswer 列表页默认不带答案，避免"翻答案代替思考"
     */
    public Map<String, Object> page(Long categoryId, String keyword, Integer difficulty,
                                    Boolean onlyHot, long pageNo, long pageSize, boolean withAnswer) {
        QueryWrapper<Question> qw = new QueryWrapper<>();
        if (categoryId != null) {
            qw.eq("category_id", categoryId);
        }
        if (difficulty != null) {
            qw.eq("difficulty", difficulty);
        }
        if (Boolean.TRUE.equals(onlyHot)) {
            qw.eq("hot", 1);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like("title", kw).or().like("tags", kw));
        }
        qw.orderByDesc("hot").orderByAsc("id");
        if (!withAnswer) {
            qw.select(Question.class, f -> !"answer".equals(f.getColumn()));
        }

        Page<Question> page = questionMapper.selectPage(new Page<>(pageNo, Math.min(pageSize, 100)), qw);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("page", page.getCurrent());
        result.put("size", page.getSize());
        result.put("records", page.getRecords());
        return result;
    }

    public Question detail(Long id) {
        Question q = questionMapper.selectById(id);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        return q;
    }

    /**
     * 供简历分析页跳转使用：按关键词找最相关的几道题。
     */
    public List<Question> search(String keyword, int limit) {
        QueryWrapper<Question> qw = new QueryWrapper<>();
        qw.and(w -> w.like("title", keyword).or().like("tags", keyword));
        qw.orderByDesc("hot").last("LIMIT " + Math.min(limit, 20));
        return questionMapper.selectList(qw);
    }
}
