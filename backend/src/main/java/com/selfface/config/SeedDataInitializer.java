package com.selfface.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfface.entity.Category;
import com.selfface.entity.Question;
import com.selfface.mapper.CategoryMapper;
import com.selfface.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动时把 resources/seed/*.json 增量导入题库。
 * 幂等策略：分类按 code 复用，题目按 title 去重——往 seed 里加新题后重启即可生效，不会产生重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedDataInitializer implements ApplicationRunner {

    private static final String SEED_PATTERN = "classpath:seed/*.json";

    private final CategoryMapper categoryMapper;
    private final QuestionMapper questionMapper;
    private final ObjectMapper mapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
        if (resources.length == 0) {
            log.warn("没有找到 seed 题库文件，题库将保持为空");
            return;
        }
        Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));

        Set<String> knownTitles = existingTitles();
        Map<String, Long> categoryIdByCode = existingCategories();

        int newCategories = 0;
        int newQuestions = 0;
        for (Resource resource : resources) {
            JsonNode root;
            try (var in = resource.getInputStream()) {
                root = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("解析题库文件 {} 失败，已跳过：{}", resource.getFilename(), e.getMessage());
                continue;
            }

            for (JsonNode c : root.path("categories")) {
                String code = c.path("code").asText("").trim();
                if (code.isEmpty() || categoryIdByCode.containsKey(code)) {
                    continue;
                }
                Category category = new Category();
                category.setCode(code);
                category.setName(c.path("name").asText(code));
                category.setDescription(c.path("description").asText(""));
                category.setSortOrder(c.path("sortOrder").asInt(0));
                categoryMapper.insert(category);
                categoryIdByCode.put(code, category.getId());
                newCategories++;
            }

            for (JsonNode q : root.path("questions")) {
                String title = q.path("title").asText("").trim();
                Long categoryId = categoryIdByCode.get(q.path("category").asText(""));
                if (title.isEmpty() || categoryId == null || knownTitles.contains(title)) {
                    continue;
                }
                Question question = new Question();
                question.setCategoryId(categoryId);
                question.setTitle(title);
                question.setAnswer(q.path("answer").asText(""));
                question.setDifficulty(q.path("difficulty").asInt(2));
                question.setTags(q.path("tags").asText(""));
                question.setHot(q.path("hot").asInt(0));
                question.setCreatedAt(LocalDateTime.now());
                questionMapper.insert(question);
                knownTitles.add(title);
                newQuestions++;
            }
        }

        long total = questionMapper.selectCount(null);
        log.info("题库就绪：本次新增分类 {} 个、题目 {} 道，现共 {} 道", newCategories, newQuestions, total);
    }

    private Set<String> existingTitles() {
        List<Question> all = questionMapper.selectList(
                new LambdaQueryWrapper<Question>().select(Question::getTitle));
        Set<String> titles = new HashSet<>();
        all.forEach(q -> titles.add(q.getTitle()));
        return titles;
    }

    private Map<String, Long> existingCategories() {
        Map<String, Long> map = new HashMap<>();
        categoryMapper.selectList(null).forEach(c -> map.put(c.getCode(), c.getId()));
        return map;
    }
}
