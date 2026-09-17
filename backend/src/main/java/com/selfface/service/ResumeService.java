package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.selfface.common.BizException;
import com.selfface.common.ResumeStatus;
import com.selfface.entity.LlmSetting;
import com.selfface.entity.ResumeAnalysis;
import com.selfface.mapper.ResumeAnalysisMapper;
import com.selfface.resume.ResumeTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final long MAX_SIZE = 10 * 1024 * 1024L;
    /** 送进模型的简历正文上限。个别 PDF 抽出几万字，直接全量塞进 prompt 会白白烧 token */
    private static final int MAX_RESUME_CHARS = 30000;
    /** 超过这个时间还停在 PENDING/RUNNING 的记录，视为僵死任务，不再阻塞用户提交新的 */
    private static final int IN_FLIGHT_WINDOW_MINUTES = 30;

    private final ResumeTextExtractor extractor;
    private final ResumeAnalysisMapper analysisMapper;
    private final LlmSettingService llmSettingService;
    private final ResumeAnalysisJob analysisJob;

    /**
     * 提交一份简历做分析：校验 + 抽文本 + 建任务记录后立刻返回，真正的模型调用在后台跑。
     *
     * <p>刻意不加 {@code @Transactional}：任务记录必须在派发异步任务之前真正提交，
     * 否则后台线程按 id 去查会查不到这一行（读不到未提交的数据）。
     */
    public Map<String, Object> analyze(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请先选择一份简历文件");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException("文件不能超过 10MB");
        }
        String fileName = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage());
        }

        // 配置校验放在派发之前：没配 Key 就立刻失败，不占用后台线程、不留垃圾记录
        LlmSetting cfg = llmSettingService.requireConfigured(userId);

        String text = extractor.extract(fileName, bytes);
        if (text == null || text.isBlank()) {
            throw new BizException("没能从这份文件里读到文字。如果是扫描件 PDF，请先转成文字版再上传。");
        }
        if (text.length() > MAX_RESUME_CHARS) {
            log.info("简历正文 {} 字，截断到 {} 字后送入模型", text.length(), MAX_RESUME_CHARS);
            text = text.substring(0, MAX_RESUME_CHARS);
        }

        // 并发保护：同一用户同时只允许一个进行中的任务，避免连点两次花掉两份 token
        // 内联常量而非占位符：这是编译期常量，且 INTERVAL 后接占位符在部分驱动版本上不生效
        Long inFlight = analysisMapper.selectCount(new LambdaQueryWrapper<ResumeAnalysis>()
                .eq(ResumeAnalysis::getUserId, userId)
                .in(ResumeAnalysis::getStatus, ResumeStatus.PENDING, ResumeStatus.RUNNING)
                .apply("created_at > DATE_SUB(NOW(), INTERVAL " + IN_FLIGHT_WINDOW_MINUTES + " MINUTE)"));
        if (inFlight != null && inFlight > 0) {
            throw new BizException("已有一个分析任务在进行中，请等它结束再上传");
        }

        ResumeAnalysis record = new ResumeAnalysis();
        record.setUserId(userId);
        record.setFileName(fileName);
        record.setRawText(text);
        record.setStatus(ResumeStatus.PENDING);
        record.setModel(cfg.getModel());
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        analysisMapper.insert(record);

        try {
            analysisJob.run(record.getId(), userId);
        } catch (TaskRejectedException e) {
            // 队列满：把记录标成失败，别让前端拿着 PENDING 一直轮询
            record.setStatus(ResumeStatus.FAILED);
            record.setErrorMsg("当前排队任务较多，请稍后重新提交");
            record.setUpdatedAt(LocalDateTime.now());
            analysisMapper.updateById(record);
            throw new BizException("当前排队任务较多，请稍后重试");
        }

        log.info("简历分析任务已受理 recordId={} userId={} file={}", record.getId(), userId, fileName);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", record.getId());
        m.put("fileName", fileName);
        m.put("status", ResumeStatus.PENDING);
        m.put("model", cfg.getModel());
        return m;
    }

    public List<Map<String, Object>> list(Long userId, int limit) {
        List<ResumeAnalysis> list = analysisMapper.selectList(new LambdaQueryWrapper<ResumeAnalysis>()
                .eq(ResumeAnalysis::getUserId, userId)
                .orderByDesc(ResumeAnalysis::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 50)));
        return list.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("fileName", r.getFileName());
            m.put("summary", r.getSummary());
            m.put("status", r.getStatus());
            m.put("model", r.getModel());
            m.put("tokens", (r.getPromptTokens() == null ? 0 : r.getPromptTokens())
                    + (r.getCompletionTokens() == null ? 0 : r.getCompletionTokens()));
            m.put("createdAt", r.getCreatedAt());
            m.put("errorMsg", r.getErrorMsg());
            return m;
        }).toList();
    }

    public Map<String, Object> detail(Long userId, Long id) {
        ResumeAnalysis r = analysisMapper.selectById(id);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BizException(404, "分析记录不存在");
        }
        return toView(r);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        ResumeAnalysis r = analysisMapper.selectById(id);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BizException(404, "分析记录不存在");
        }
        analysisMapper.deleteById(id);
    }

    private Map<String, Object> toView(ResumeAnalysis r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("fileName", r.getFileName());
        m.put("status", r.getStatus());
        m.put("summary", r.getSummary());
        m.put("profileJson", r.getProfileJson());
        m.put("questionsJson", r.getQuestionsJson());
        m.put("errorMsg", r.getErrorMsg());
        m.put("model", r.getModel());
        m.put("promptTokens", r.getPromptTokens());
        m.put("completionTokens", r.getCompletionTokens());
        m.put("textLength", r.getRawText() == null ? 0 : r.getRawText().length());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
