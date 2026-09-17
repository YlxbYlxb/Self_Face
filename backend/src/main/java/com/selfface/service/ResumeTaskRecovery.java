package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.selfface.common.ResumeStatus;
import com.selfface.entity.ResumeAnalysis;
import com.selfface.mapper.ResumeAnalysisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时清理「上次进程被杀掉时留在半路」的分析任务。
 *
 * <p>任务状态存在数据库里，但执行它的线程在内存里。进程重启后这些记录会永远停在
 * PENDING/RUNNING，前端的轮询会一直转圈，而且并发保护会一直拒绝这个用户提交新简历。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeTaskRecovery implements ApplicationRunner {

    private final ResumeAnalysisMapper analysisMapper;

    @Override
    public void run(ApplicationArguments args) {
        int fixed = analysisMapper.update(null, new LambdaUpdateWrapper<ResumeAnalysis>()
                .in(ResumeAnalysis::getStatus, ResumeStatus.PENDING, ResumeStatus.RUNNING)
                .set(ResumeAnalysis::getStatus, ResumeStatus.FAILED)
                .set(ResumeAnalysis::getErrorMsg, "服务重启导致分析中断，请重新上传")
                .set(ResumeAnalysis::getUpdatedAt, LocalDateTime.now()));
        if (fixed > 0) {
            log.warn("启动时清理了 {} 条中断的简历分析任务", fixed);
        }
    }
}
