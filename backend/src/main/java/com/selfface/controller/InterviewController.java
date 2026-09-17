package com.selfface.controller;

import com.selfface.common.R;
import com.selfface.security.CurrentUser;
import com.selfface.service.InterviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模拟面试。每一次问答都会调用用户自己的大模型，所以整体走 LLM 级别的限流。
 */
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    public record StartRequest(Long resumeId, String role, String level, String type, Integer maxRounds) {
    }

    public record AnswerRequest(String answer) {
    }

    /** 开始一场面试，返回会话与面试官的第一个问题 */
    @PostMapping("/session")
    public R<Map<String, Object>> start(@RequestBody StartRequest req) {
        return R.ok(interviewService.start(CurrentUser.id(), req.resumeId(), req.role(),
                req.level(), req.type(), req.maxRounds()));
    }

    /** 提交一次回答，返回面试官的点评与追问 */
    @PostMapping("/session/{id}/answer")
    public R<Map<String, Object>> answer(@PathVariable Long id, @RequestBody AnswerRequest req) {
        return R.ok(interviewService.answer(CurrentUser.id(), id, req.answer()));
    }

    /** 结束面试并生成报告（重复调用返回已有报告） */
    @PostMapping("/session/{id}/finish")
    public R<Map<String, Object>> finish(@PathVariable Long id) {
        return R.ok(interviewService.finish(CurrentUser.id(), id));
    }

    @GetMapping("/session/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(interviewService.detail(CurrentUser.id(), id));
    }

    @GetMapping("/sessions")
    public R<List<Map<String, Object>>> list(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(interviewService.list(CurrentUser.id(), limit));
    }

    @DeleteMapping("/session/{id}")
    public R<Void> remove(@PathVariable Long id) {
        interviewService.remove(CurrentUser.id(), id);
        return R.ok();
    }
}
