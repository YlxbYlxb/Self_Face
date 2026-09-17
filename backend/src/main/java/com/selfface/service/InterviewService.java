package com.selfface.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfface.common.BizException;
import com.selfface.entity.InterviewSession;
import com.selfface.entity.InterviewTurn;
import com.selfface.entity.LlmSetting;
import com.selfface.entity.ResumeAnalysis;
import com.selfface.llm.InterviewAgent;
import com.selfface.mapper.InterviewSessionMapper;
import com.selfface.mapper.InterviewTurnMapper;
import com.selfface.mapper.ResumeAnalysisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟面试的编排。
 *
 * <p>题源优先复用简历分析的结果：那条链路已经把简历压成了画像、并预测了面试问题，
 * 这里直接拿来当面试官的背景知识，不需要用户再手动选一遍「考什么」。
 *
 * <p>为什么不做成异步任务：每一轮只是一次一问一答的模型调用（几秒），
 * 前端用「面试官正在思考」的加载态就能覆盖，没必要上轮询——
 * 简历分析之所以异步，是因为它要跑两次长调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    /** 单次回答的长度上限，超长会挤占上下文并推高成本 */
    private static final int MAX_ANSWER_CHARS = 3000;
    private static final int DEFAULT_ROUNDS = 5;
    private static final int MAX_ROUNDS_LIMIT = 8;
    /** 会话列表一次最多返回多少条 */
    private static final int MAX_LIST = 50;

    private final InterviewSessionMapper sessionMapper;
    private final InterviewTurnMapper turnMapper;
    private final ResumeAnalysisMapper resumeAnalysisMapper;
    private final InterviewAgent interviewAgent;
    private final LlmSettingService llmSettingService;
    private final ObjectMapper objectMapper;

    /**
     * 开一场新面试：建会话 → 让面试官问第一个问题 → 返回完整会话。
     */
    public Map<String, Object> start(Long userId, Long resumeId, String role, String level,
                                     String type, Integer maxRounds) {
        // 先校验模型配置：没配就直接拒绝，避免建出一场没法进行下去的会话
        LlmSetting cfg = llmSettingService.requireConfigured(userId);

        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setResumeId(resumeId);
        session.setRole(role);
        session.setLevel(normalizeLevel(level));
        session.setType(normalizeType(type));
        session.setStatus(InterviewSession.STATUS_RUNNING);
        session.setMaxRounds(normalizeRounds(maxRounds));
        session.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        try {
            InterviewAgent.Opening opening = interviewAgent.start(cfg, buildContext(session));
            appendTurn(session.getId(), InterviewTurn.ROLE_INTERVIEWER,
                    opening.kind(), opening.question(), null, null);
        } catch (RuntimeException e) {
            // 开场问题没生成出来，这场面试就是废的，直接清掉别留孤儿记录
            sessionMapper.deleteById(session.getId());
            throw e;
        }

        log.info("用户 {} 开始模拟面试 sessionId={}", userId, session.getId());
        return detail(userId, session.getId());
    }

    /**
     * 提交一次回答：记录回答 → 让面试官点评并追问 → 返回更新后的会话。
     */
    public Map<String, Object> answer(Long userId, Long sessionId, String answerText) {
        InterviewSession session = requireOwned(userId, sessionId);
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            throw new BizException("这场面试已经结束了，看看报告吧");
        }
        String answer = answerText == null ? "" : answerText.trim();
        if (answer.length() < 2) {
            throw new BizException("先写下你的回答再提交");
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_ANSWER_CHARS);
        }

        LlmSetting cfg = llmSettingService.requireConfigured(userId);
        // 先取「回答之前」的对话作为上下文，本轮回答单独传给模型
        List<InterviewTurn> history = turnMapper.listBySession(sessionId);

        appendTurn(sessionId, InterviewTurn.ROLE_CANDIDATE, InterviewTurn.KIND_ANSWER,
                answer, null, null);

        InterviewAgent.Evaluation eval = interviewAgent.reply(cfg, buildContext(session), history, answer);

        appendTurn(sessionId, InterviewTurn.ROLE_INTERVIEWER, eval.kind(),
                eval.nextQuestion(), eval.score(), eval.comment());

        return detail(userId, sessionId);
    }

    /**
     * 结束面试并生成报告。重复调用返回已有报告（幂等），不会重复花钱。
     */
    public Map<String, Object> finish(Long userId, Long sessionId) {
        InterviewSession session = requireOwned(userId, sessionId);
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())
                && session.getReportJson() != null) {
            return detail(userId, sessionId);
        }

        List<InterviewTurn> history = turnMapper.listBySession(sessionId);
        if (history.isEmpty()) {
            throw new BizException("这场面试还没有内容，没法出报告");
        }

        LlmSetting cfg = llmSettingService.requireConfigured(userId);
        JsonNode report = interviewAgent.report(cfg, buildContext(session), history);

        session.setStatus(InterviewSession.STATUS_FINISHED);
        session.setReportJson(report.toString());
        session.setOverallScore(report.path("overallScore").asInt(0));
        session.setFinishedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        log.info("用户 {} 的模拟面试 sessionId={} 结束，总评 {}",
                userId, sessionId, session.getOverallScore());
        return detail(userId, sessionId);
    }

    public Map<String, Object> detail(Long userId, Long sessionId) {
        InterviewSession session = requireOwned(userId, sessionId);
        List<InterviewTurn> turns = turnMapper.listBySession(sessionId);

        long answered = turns.stream()
                .filter(t -> InterviewTurn.ROLE_CANDIDATE.equals(t.getRole()))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.getId());
        result.put("role", session.getRole());
        result.put("level", session.getLevel());
        result.put("type", session.getType());
        result.put("status", session.getStatus());
        result.put("maxRounds", session.getMaxRounds());
        result.put("overallScore", session.getOverallScore());
        result.put("startedAt", session.getStartedAt());
        result.put("finishedAt", session.getFinishedAt());
        result.put("round", answered);
        result.put("turns", turns);
        result.put("report", parseReport(session.getReportJson()));
        return result;
    }

    public List<Map<String, Object>> list(Long userId, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIST);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InterviewSession s : sessionMapper.listByUser(userId, size)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("role", s.getRole());
            item.put("level", s.getLevel());
            item.put("type", s.getType());
            item.put("status", s.getStatus());
            item.put("overallScore", s.getOverallScore());
            item.put("startedAt", s.getStartedAt());
            item.put("finishedAt", s.getFinishedAt());
            rows.add(item);
        }
        return rows;
    }

    @Transactional
    public void remove(Long userId, Long sessionId) {
        requireOwned(userId, sessionId);
        turnMapper.deleteBySession(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    // ------------------------------------------------------------------ 内部

    private InterviewSession requireOwned(Long userId, Long sessionId) {
        InterviewSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        // 一条消息说清「不存在」和「不是你的」，避免泄露别人有没有这条记录
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException("面试记录不存在");
        }
        return session;
    }

    private InterviewTurn appendTurn(Long sessionId, String role, String kind,
                                     String content, Integer score, String comment) {
        InterviewTurn turn = new InterviewTurn();
        turn.setSessionId(sessionId);
        turn.setSeq(turnMapper.maxSeq(sessionId) + 1);
        turn.setRole(role);
        turn.setKind(kind);
        turn.setContent(content);
        turn.setScore(score);
        turn.setComment(comment);
        turn.setCreatedAt(LocalDateTime.now());
        turnMapper.insert(turn);
        return turn;
    }

    /**
     * 组装面试官需要的背景。简历解析失败不影响面试——只是少了「针对简历提问」这一层。
     */
    private InterviewAgent.Context buildContext(InterviewSession session) {
        String brief = null;
        List<String> seeds = new ArrayList<>();
        if (session.getResumeId() != null) {
            ResumeAnalysis resume = resumeAnalysisMapper.selectById(session.getResumeId());
            // 归属校验：不能拿别人的简历来出题
            if (resume != null && resume.getUserId().equals(session.getUserId())) {
                brief = briefOf(resume.getProfileJson());
                seeds = questionsOf(resume.getQuestionsJson());
            }
        }
        return new InterviewAgent.Context(
                session.getRole(), session.getLevel(), session.getType(), brief, seeds);
    }

    private String briefOf(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) {
            return null;
        }
        try {
            JsonNode p = objectMapper.readTree(profileJson);
            StringBuilder sb = new StringBuilder();
            String edu = p.path("education").asText("");
            if (!edu.isBlank()) {
                sb.append("学历：").append(edu).append('\n');
            }
            List<String> skills = new ArrayList<>();
            for (JsonNode s : p.path("skills")) {
                String name = s.path("name").asText("");
                if (!name.isBlank()) {
                    skills.add(name);
                }
            }
            if (!skills.isEmpty()) {
                sb.append("技能：").append(String.join("、", skills)).append('\n');
            }
            for (JsonNode proj : p.path("projects")) {
                String name = proj.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                List<String> stack = new ArrayList<>();
                for (JsonNode tech : proj.path("techStack")) {
                    stack.add(tech.asText(""));
                }
                sb.append("项目：").append(name);
                if (!stack.isEmpty()) {
                    sb.append("（").append(String.join("/", stack)).append("）");
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("简历画像解析失败，本次面试不携带简历背景：{}", e.getMessage());
            return null;
        }
    }

    private List<String> questionsOf(String questionsJson) {
        List<String> list = new ArrayList<>();
        if (questionsJson == null || questionsJson.isBlank()) {
            return list;
        }
        try {
            JsonNode root = objectMapper.readTree(questionsJson);
            for (JsonNode group : root.path("groups")) {
                for (JsonNode q : group.path("questions")) {
                    String text = q.path("question").asText("");
                    if (!text.isBlank()) {
                        list.add(text);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("简历预测问题解析失败，本次面试不携带参考问题：{}", e.getMessage());
        }
        return list;
    }

    private Object parseReport(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(reportJson);
        } catch (Exception e) {
            log.warn("面试报告解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static String normalizeLevel(String level) {
        if ("mid".equals(level) || "senior".equals(level)) {
            return level;
        }
        return "junior";
    }

    private static String normalizeType(String type) {
        if ("project".equals(type) || "comprehensive".equals(type)) {
            return type;
        }
        return "technical";
    }

    private static int normalizeRounds(Integer rounds) {
        if (rounds == null) {
            return DEFAULT_ROUNDS;
        }
        return Math.min(Math.max(rounds, 1), MAX_ROUNDS_LIMIT);
    }
}
