package com.selfface.service;

import com.selfface.common.ResumeStatus;
import com.selfface.entity.LlmSetting;
import com.selfface.entity.ResumeAnalysis;
import com.selfface.entity.User;
import com.selfface.llm.ResumeAnalyzer;
import com.selfface.mapper.ResumeAnalysisMapper;
import com.selfface.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 简历分析的后台执行者。
 *
 * <p>刻意做成独立 bean：{@code @Async} 依赖 Spring 代理，如果写在 ResumeService 内部
 * 由自己调用（this.xxx()），代理不会生效，任务仍会在请求线程里同步跑完。
 *
 * <p>另一个注意点：状态更新不带 {@code @Transactional}。整个任务要跑几十秒并做多次
 * 独立的状态写，把长事务摊开反而会把 RUNNING 状态一直锁到结束，前端轮询永远看不到中间态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeAnalysisJob {

    /** error_msg 列是 VARCHAR(1024)，超长会再抛一个异常，把真实失败原因盖掉 */
    private static final int MAX_ERROR_LEN = 1000;

    private final ResumeAnalyzer analyzer;
    private final ResumeAnalysisMapper analysisMapper;
    private final LlmSettingService llmSettingService;
    private final UserMapper userMapper;

    @Async("resumeExecutor")
    public void run(Long recordId, Long userId) {
        ResumeAnalysis record = analysisMapper.selectById(recordId);
        if (record == null) {
            log.warn("简历分析记录 {} 已不存在，跳过", recordId);
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            record.setStatus(ResumeStatus.RUNNING);
            record.setUpdatedAt(LocalDateTime.now());
            analysisMapper.updateById(record);

            LlmSetting cfg = llmSettingService.requireConfigured(userId);
            User user = userMapper.selectById(userId);

            ResumeAnalyzer.AnalyzeResult result = analyzer.analyze(cfg, user, record.getRawText());

            record.setProfileJson(result.profileJson());
            record.setQuestionsJson(result.questionsJson());
            record.setSummary(result.summary());
            record.setModel(result.usage().model());
            record.setPromptTokens(result.usage().promptTokens());
            record.setCompletionTokens(result.usage().completionTokens());
            record.setStatus(ResumeStatus.SUCCESS);
            record.setErrorMsg(null);
            record.setUpdatedAt(LocalDateTime.now());
            analysisMapper.updateById(record);

            log.info("简历分析完成 recordId={} userId={} 耗时 {} ms，命中题库 {} 道，tokens {}+{}",
                    recordId, userId, System.currentTimeMillis() - startedAt, result.bankHitCount(),
                    result.usage().promptTokens(), result.usage().completionTokens());
        } catch (Exception e) {
            // 后台任务没有返回值，失败必须落到库里，否则用户只看到一直转圈
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > MAX_ERROR_LEN) {
                msg = msg.substring(0, MAX_ERROR_LEN);
            }
            record.setStatus(ResumeStatus.FAILED);
            record.setErrorMsg(msg);
            record.setUpdatedAt(LocalDateTime.now());
            analysisMapper.updateById(record);
            log.warn("简历分析失败 recordId={} userId={} 耗时 {} ms",
                    recordId, userId, System.currentTimeMillis() - startedAt, e);
        }
    }
}
