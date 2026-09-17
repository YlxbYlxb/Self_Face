package com.selfface.controller;

import com.selfface.common.R;
import com.selfface.entity.PracticeRecord;
import com.selfface.security.CurrentUser;
import com.selfface.service.PracticeService;
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
@RequestMapping("/api/practice")
@RequiredArgsConstructor
public class PracticeController {

    private final PracticeService practiceService;

    public record SubmitRequest(Long questionId, Integer mastery, String answerText, Integer costSeconds) {
    }

    public record AppendRequest(Long questionId) {
    }

    /** 今日题单，首次调用时即时组卷 */
    @GetMapping("/today")
    public R<List<Map<String, Object>>> today() {
        return R.ok(practiceService.todayTasks(CurrentUser.id()));
    }

    @PostMapping("/submit")
    public R<Map<String, Object>> submit(@RequestBody SubmitRequest req) {
        return R.ok(practiceService.submit(CurrentUser.id(), req.questionId(), req.mastery(),
                req.answerText(), req.costSeconds()));
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(practiceService.stats(CurrentUser.id()));
    }

    @GetMapping("/wrong-book")
    public R<List<Map<String, Object>>> wrongBook(@RequestParam(required = false) Integer mastery) {
        return R.ok(practiceService.wrongBook(CurrentUser.id(), mastery));
    }

    @GetMapping("/history/{questionId}")
    public R<List<PracticeRecord>> history(@PathVariable Long questionId) {
        return R.ok(practiceService.history(CurrentUser.id(), questionId));
    }

    /** 把某道题追加进今日题单 */
    @PostMapping("/append")
    public R<Void> append(@RequestBody AppendRequest req) {
        practiceService.appendToToday(CurrentUser.id(), req.questionId());
        return R.ok();
    }

    public record AppendBatchRequest(List<Long> questionIds) {
    }

    /**
     * 把一批题追加进今日题单，用于 JD 定向题单的「一键开始刷」。
     * 已经在题单里的会被跳过，返回实际新增条数。
     */
    @PostMapping("/append-batch")
    public R<Map<String, Object>> appendBatch(@RequestBody AppendBatchRequest req) {
        int inserted = practiceService.appendBatchToToday(CurrentUser.id(), req.questionIds());
        return R.ok(Map.of("inserted", inserted));
    }
}
