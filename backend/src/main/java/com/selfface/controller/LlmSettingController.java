package com.selfface.controller;

import com.selfface.common.R;
import com.selfface.security.CurrentUser;
import com.selfface.service.LlmSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmSettingController {

    private final LlmSettingService llmSettingService;

    public record SaveRequest(String baseUrl, String apiKey, String model,
                              Double temperature, Integer timeoutSeconds) {
    }

    @GetMapping("/setting")
    public R<LlmSettingService.View> get() {
        return R.ok(llmSettingService.get(CurrentUser.id()));
    }

    @PutMapping("/setting")
    public R<LlmSettingService.View> save(@RequestBody SaveRequest req) {
        return R.ok(llmSettingService.save(CurrentUser.id(), req.baseUrl(), req.apiKey(),
                req.model(), req.temperature(), req.timeoutSeconds()));
    }

    @PostMapping("/test")
    public R<Map<String, String>> test() {
        return R.ok(Map.of("message", llmSettingService.test(CurrentUser.id())));
    }
}
