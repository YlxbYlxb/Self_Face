package com.selfface.controller;

import com.selfface.common.BizException;
import com.selfface.common.R;
import com.selfface.entity.Question;
import com.selfface.service.QuestionImportService;
import com.selfface.service.QuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionImportService questionImportService;

    @GetMapping("/categories")
    public R<List<Map<String, Object>>> categories() {
        return R.ok(questionService.categoriesWithCount());
    }

    @GetMapping("/questions")
    public R<Map<String, Object>> page(@RequestParam(required = false) Long categoryId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer difficulty,
                                       @RequestParam(required = false) Boolean onlyHot,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(defaultValue = "false") boolean withAnswer) {
        return R.ok(questionService.page(categoryId, keyword, difficulty, onlyHot, page, size, withAnswer));
    }

    @GetMapping("/questions/{id}")
    public R<Question> detail(@PathVariable Long id) {
        return R.ok(questionService.detail(id));
    }

    /**
     * 导入题库。请求体里传 JSON 文本而不是 multipart 文件，
     * 这样「粘贴 JSON」和「上传文件」两种前端交互可以共用同一个接口。
     */
    @PostMapping("/questions/import")
    public R<QuestionImportService.Result> importQuestions(@RequestBody ImportRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new BizException("请提供要导入的 JSON 内容");
        }
        return R.ok(questionImportService.importJson(request.content(), request.autoCreateOrDefault()));
    }

    /**
     * @param autoCreateCategory 题目里出现的未知分类是否自动创建，默认是。
     *                           关掉之后未知分类的题目会被跳过并在结果里说明。
     */
    public record ImportRequest(String content, Boolean autoCreateCategory) {
        boolean autoCreateOrDefault() {
            return autoCreateCategory == null || autoCreateCategory;
        }
    }
}
