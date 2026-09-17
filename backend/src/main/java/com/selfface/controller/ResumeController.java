package com.selfface.controller;

import com.selfface.common.R;
import com.selfface.security.CurrentUser;
import com.selfface.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resume")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    /** 上传简历，立刻返回任务 id 与 PENDING 状态；真正的分析在后台线程跑，用 GET /resume/{id} 轮询 */
    @PostMapping("/analyze")
    public R<Map<String, Object>> analyze(@RequestParam("file") MultipartFile file) {
        return R.ok(resumeService.analyze(CurrentUser.id(), file));
    }

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(resumeService.list(CurrentUser.id(), limit));
    }

    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(resumeService.detail(CurrentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        resumeService.delete(CurrentUser.id(), id);
        return R.ok();
    }
}
