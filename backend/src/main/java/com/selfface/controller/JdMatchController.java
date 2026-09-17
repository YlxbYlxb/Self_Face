package com.selfface.controller;

import com.selfface.common.R;
import com.selfface.security.CurrentUser;
import com.selfface.service.JdMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * JD 定向题单。
 */
@RestController
@RequestMapping("/api/jd")
@RequiredArgsConstructor
public class JdMatchController {

    private final JdMatchService jdMatchService;

    public record AnalyzeRequest(String jdText, String role) {
    }

    /**
     * 分析一份岗位 JD：抽取技术关键词 → 题库召回 → 返回命中情况与推荐题单。
     * 会真实调用用户自己的大模型，所以走 LLM 级别的限流。
     */
    @PostMapping("/analyze")
    public R<Map<String, Object>> analyze(@RequestBody AnalyzeRequest req) {
        return R.ok(jdMatchService.analyze(CurrentUser.id(), req.jdText(), req.role()));
    }
}
