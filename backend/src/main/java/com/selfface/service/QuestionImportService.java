package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfface.common.BizException;
import com.selfface.entity.Category;
import com.selfface.entity.Question;
import com.selfface.mapper.CategoryMapper;
import com.selfface.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 JSON 导入题库。
 *
 * <p>支持两种结构，内部题库（resources/seed/*.json）用的就是第一种：
 * <pre>
 * { "categories": [ { "code": "java-basic", "name": "Java 基础" } ],
 *   "questions":  [ { "title": "...", "answer": "...", "category": "java-basic",
 *                     "difficulty": 2, "tags": "..." } ] }
 *
 * [ { "title": "...", "category": "Java 基础", "answer": "..." } ]
 * </pre>
 *
 * <p>幂等性沿用 seed 的做法：分类按 code 复用，题目按 title 去重，
 * 因此同一份文件重复导入不会产生重复题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionImportService {

    /** 单次导入上限：一次灌太多既拖慢请求，也会把格式错误整体放大 */
    static final int MAX_QUESTIONS_PER_IMPORT = 500;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_ANSWER_LENGTH = 20000;
    private static final int MAX_TAGS_LENGTH = 200;
    private static final int MAX_REPORTED_REASONS = 20;
    private static final int INSERT_BATCH_SIZE = 200;
    private static final int CATEGORY_SORT_STEP = 10;

    private final QuestionMapper questionMapper;
    private final CategoryMapper categoryMapper;
    private final ObjectMapper mapper;

    /**
     * @param total          提交的题目条数
     * @param inserted       实际写入条数
     * @param skipped        被跳过的条数（重复、缺字段、分类未知等）
     * @param categoriesCreated 本次自动新建的分类数
     * @param reasons        跳过原因，最多 20 条，避免响应体过大
     */
    public record Result(int total, int inserted, int skipped, int categoriesCreated,
                         List<String> reasons) {
    }

    @Transactional
    public Result importJson(String json, boolean autoCreateCategory) {
        JsonNode root = parse(json);
        JsonNode questionsNode = resolveQuestions(root);
        JsonNode categoriesNode = resolveCategories(root);

        Map<String, Long> idByCode = new HashMap<>();
        Map<String, Long> idByName = new HashMap<>();
        int maxSortOrder = 0;
        for (Category c : categoryMapper.selectList(null)) {
            idByCode.put(c.getCode(), c.getId());
            if (c.getName() != null) {
                idByName.put(c.getName(), c.getId());
            }
            if (c.getSortOrder() != null) {
                maxSortOrder = Math.max(maxSortOrder, c.getSortOrder());
            }
        }

        int categoriesCreated = 0;
        for (JsonNode c : categoriesNode) {
            String code = text(c, "code");
            String name = text(c, "name");
            if (code.isEmpty() && name.isEmpty()) {
                continue;
            }
            String resolvedCode = code.isEmpty() ? slugify(name) : code;
            String resolvedName = name.isEmpty() ? resolvedCode : name;
            if (idByCode.containsKey(resolvedCode)) {
                continue;
            }
            maxSortOrder += CATEGORY_SORT_STEP;
            Category category = new Category();
            category.setCode(resolvedCode);
            category.setName(resolvedName);
            category.setDescription(text(c, "description"));
            category.setSortOrder(c.path("sortOrder").asInt(maxSortOrder));
            categoryMapper.insert(category);
            idByCode.put(resolvedCode, category.getId());
            idByName.put(resolvedName, category.getId());
            categoriesCreated++;
        }

        Set<String> knownTitles = existingTitles();
        List<Question> pending = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int skipped = 0;

        for (JsonNode q : questionsNode) {
            String title = text(q, "title");
            if (title.isEmpty()) {
                skipped++;
                addReason(reasons, skipped, "有一条题目缺少 title");
                continue;
            }
            if (title.length() > MAX_TITLE_LENGTH) {
                skipped++;
                addReason(reasons, skipped, "题干超过 " + MAX_TITLE_LENGTH + " 字，已跳过：" + brief(title));
                continue;
            }
            // 先判重再解析分类：重复题不该顺手建出一个用不上的分类
            if (!knownTitles.add(title)) {
                skipped++;
                addReason(reasons, skipped, "标题已存在，跳过：" + brief(title));
                continue;
            }

            String rawCategory = text(q, "category");
            Long categoryId = resolveCategory(rawCategory, idByCode, idByName);
            if (categoryId == null) {
                if (!autoCreateCategory) {
                    skipped++;
                    addReason(reasons, skipped, "分类「" + rawCategory + "」不存在，跳过：" + brief(title));
                    continue;
                }
                maxSortOrder += CATEGORY_SORT_STEP;
                Category created = new Category();
                created.setCode(slugify(rawCategory));
                created.setName(rawCategory.isEmpty() ? "未分类" : rawCategory);
                created.setDescription("导入题目时自动创建");
                created.setSortOrder(maxSortOrder);
                categoryMapper.insert(created);
                idByCode.put(created.getCode(), created.getId());
                idByName.put(created.getName(), created.getId());
                categoriesCreated++;
                categoryId = created.getId();
            }

            pending.add(buildQuestion(q, title, categoryId));
        }

        for (int i = 0; i < pending.size(); i += INSERT_BATCH_SIZE) {
            List<Question> batch = pending.subList(i, Math.min(i + INSERT_BATCH_SIZE, pending.size()));
            questionMapper.batchInsert(batch);
        }

        log.info("题库导入完成：提交 {} 条，写入 {} 条，跳过 {} 条，新建分类 {} 个",
                questionsNode.size(), pending.size(), skipped, categoriesCreated);
        return new Result(questionsNode.size(), pending.size(), skipped, categoriesCreated, reasons);
    }

    private Question buildQuestion(JsonNode q, String title, Long categoryId) {
        Question question = new Question();
        question.setCategoryId(categoryId);
        question.setTitle(title);
        question.setAnswer(truncate(text(q, "answer"), MAX_ANSWER_LENGTH));
        // 难度超出 1-3 一律夹到区间内，而不是拒收整条题
        question.setDifficulty(Math.max(1, Math.min(3, q.path("difficulty").asInt(2))));
        question.setTags(truncate(text(q, "tags"), MAX_TAGS_LENGTH));
        question.setHot(q.path("hot").asInt(0));
        question.setCreatedAt(LocalDateTime.now());
        return question;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BizException("内容为空，没有可导入的题目");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || root.isNull()) {
                throw new BizException("内容为空，没有可导入的题目");
            }
            return root;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 不回传 Jackson 的原始报错，里面会带类名和行号
            throw new BizException("JSON 格式有误，请检查是否为合法的 JSON 数组或对象");
        }
    }

    private JsonNode resolveQuestions(JsonNode root) {
        JsonNode node = root.isArray() ? root : root.path("questions");
        if (!node.isArray()) {
            throw new BizException("没有找到题目数组：顶层可以是数组，或是含 questions 字段的对象");
        }
        if (node.isEmpty()) {
            throw new BizException("没有解析到任何题目");
        }
        if (node.size() > MAX_QUESTIONS_PER_IMPORT) {
            throw new BizException("单次最多导入 " + MAX_QUESTIONS_PER_IMPORT + " 道题，当前 "
                    + node.size() + " 道，请分批导入");
        }
        return node;
    }

    private JsonNode resolveCategories(JsonNode root) {
        JsonNode node = root.path("categories");
        return node.isArray() ? node : mapper.createArrayNode();
    }

    private Long resolveCategory(String raw, Map<String, Long> idByCode, Map<String, Long> idByName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        // 允许写 code（java-basic），也允许直接写展示名（Java 基础）
        Long byCode = idByCode.get(key);
        return byCode != null ? byCode : idByName.get(key);
    }

    /**
     * 把任意分类名压成 ASCII 编码。中文名没法直接当 code，
     * 用哈希兜底保证唯一且稳定（同一个名字每次都得到同一个 code）。
     */
    private String slugify(String raw) {
        String name = raw == null ? "" : raw.trim();
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (!slug.isEmpty()) {
            return slug;
        }
        return "custom-" + Integer.toHexString(name.hashCode());
    }

    private Set<String> existingTitles() {
        // 只取 title 列：题库上千条时，把 answer 一起拉回内存纯属浪费。
        // 这里用 QueryWrapper 写显式列名而不是 LambdaQueryWrapper.select(Question::getTitle)——
        // 后者解析 lambda 需要 MyBatis-Plus 的 TableInfo 元数据，在纯单元测试里没初始化会 NPE。
        List<Question> all = questionMapper.selectList(
                new QueryWrapper<Question>().select("title"));
        Set<String> titles = new HashSet<>(all.size());
        all.forEach(q -> titles.add(q.getTitle()));
        return titles;
    }

    private static void addReason(List<String> reasons, int skippedSoFar, String reason) {
        if (reasons.size() < MAX_REPORTED_REASONS) {
            reasons.add(reason);
        } else if (reasons.size() == MAX_REPORTED_REASONS) {
            reasons.add("（跳过原因较多，仅显示前 " + MAX_REPORTED_REASONS + " 条，共 " + skippedSoFar + " 条）");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String brief(String value) {
        return value.length() <= 30 ? value : value.substring(0, 30) + "…";
    }
}
