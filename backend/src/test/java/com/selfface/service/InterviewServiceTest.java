package com.selfface.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模拟面试的边界行为。
 *
 * <p>三条最要紧的约束：<b>读不到别人的会话</b>、<b>结束的面试不能继续答</b>、
 * <b>重复结束不会重复烧模型额度</b>。最后一条尤其容易被改坏——
 * 报告生成是一次昂贵的调用，幂等是省钱的关键。
 */
@ExtendWith(MockitoExtension.class)
class InterviewServiceTest {

    private static final long USER = 1L;
    private static final long SESSION = 7L;

    @Mock
    private InterviewSessionMapper sessionMapper;
    @Mock
    private InterviewTurnMapper turnMapper;
    @Mock
    private ResumeAnalysisMapper resumeAnalysisMapper;
    @Mock
    private InterviewAgent interviewAgent;
    @Mock
    private LlmSettingService llmSettingService;

    private InterviewService service;

    @BeforeEach
    void setUp() {
        service = new InterviewService(sessionMapper, turnMapper, resumeAnalysisMapper,
                interviewAgent, llmSettingService, new ObjectMapper());
    }

    @Test
    @DisplayName("读不到别人的会话，也不暴露记录是否存在")
    void rejectsSessionOwnedByOthers() {
        InterviewSession other = session(99L);
        when(sessionMapper.selectById(SESSION)).thenReturn(other);

        BizException e = assertThrows(BizException.class, () -> service.detail(USER, SESSION));

        assertEquals("面试记录不存在", e.getMessage());
    }

    @Test
    @DisplayName("已结束的面试不允许继续作答")
    void rejectsAnswerOnFinishedSession() {
        InterviewSession finished = session(USER);
        finished.setStatus(InterviewSession.STATUS_FINISHED);
        when(sessionMapper.selectById(SESSION)).thenReturn(finished);

        BizException e = assertThrows(BizException.class,
                () -> service.answer(USER, SESSION, "我以为还能继续答"));

        assertTrue(e.getMessage().contains("已经结束"), "实际提示：" + e.getMessage());
        verifyNoInteractions(interviewAgent);
    }

    @Test
    @DisplayName("回答过短时直接拒绝，不消耗模型额度")
    void rejectsTooShortAnswer() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(USER));

        assertThrows(BizException.class, () -> service.answer(USER, SESSION, " "));

        verifyNoInteractions(interviewAgent, llmSettingService);
    }

    @Test
    @DisplayName("重复结束面试时直接返回已有报告，不再调用模型")
    void finishIsIdempotent() {
        InterviewSession finished = session(USER);
        finished.setStatus(InterviewSession.STATUS_FINISHED);
        finished.setReportJson("{\"overallScore\":80,\"verdict\":\"可以过\"}");
        finished.setOverallScore(80);
        when(sessionMapper.selectById(SESSION)).thenReturn(finished);
        when(turnMapper.listBySession(SESSION)).thenReturn(List.of());

        Map<String, Object> out = service.finish(USER, SESSION);

        assertEquals(80, out.get("overallScore"));
        verifyNoInteractions(interviewAgent, llmSettingService);
    }

    @Test
    @DisplayName("一次作答写入两条轮次：候选人的回答 + 面试官的点评与追问")
    void answerAppendsCandidateThenInterviewerTurn() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(USER));
        when(turnMapper.listBySession(SESSION)).thenReturn(List.of());
        when(turnMapper.maxSeq(SESSION)).thenReturn(1, 3);
        when(llmSettingService.requireConfigured(USER)).thenReturn(new LlmSetting());
        when(interviewAgent.reply(any(), any(), any(), any()))
                .thenReturn(new InterviewAgent.Evaluation(75, "说清了机制，漏了扩容细节",
                        List.of("扩容细节"), "那扩容时元素怎么迁移？", InterviewTurn.KIND_FOLLOW_UP));

        service.answer(USER, SESSION, "HashMap 用数组加链表……");

        ArgumentCaptor<InterviewTurn> captor = ArgumentCaptor.forClass(InterviewTurn.class);
        verify(turnMapper, times(2)).insert(captor.capture());
        List<InterviewTurn> saved = captor.getAllValues();

        assertEquals(InterviewTurn.ROLE_CANDIDATE, saved.get(0).getRole(), "先记候选人的回答");
        assertEquals(2, saved.get(0).getSeq().intValue(), "序号取 MAX(seq)+1");
        assertEquals(InterviewTurn.ROLE_INTERVIEWER, saved.get(1).getRole(), "再记面试官的点评与追问");
        assertEquals(4, saved.get(1).getSeq().intValue(), "两条轮次序号不能撞");
        assertEquals(75, saved.get(1).getScore().intValue(), "评分挂在面试官那一轮上");
        assertEquals(InterviewTurn.KIND_FOLLOW_UP, saved.get(1).getKind());
    }

    @Test
    @DisplayName("引用的简历不属于本人时，不拿它当出题背景")
    void ignoresResumeOwnedByOthers() {
        InterviewSession s = session(USER);
        s.setResumeId(100L);
        when(sessionMapper.selectById(SESSION)).thenReturn(s);
        when(turnMapper.listBySession(SESSION)).thenReturn(List.of());
        when(turnMapper.maxSeq(SESSION)).thenReturn(0, 2);
        when(llmSettingService.requireConfigured(USER)).thenReturn(new LlmSetting());

        ResumeAnalysis otherResume = new ResumeAnalysis();
        otherResume.setId(100L);
        otherResume.setUserId(99L);
        otherResume.setProfileJson("{\"education\":\"别人的学校\"}");
        otherResume.setQuestionsJson("{\"groups\":[{\"questions\":[{\"question\":\"别人的问题\"}]}]}");
        when(resumeAnalysisMapper.selectById(100L)).thenReturn(otherResume);

        when(interviewAgent.reply(any(), any(), any(), any()))
                .thenReturn(new InterviewAgent.Evaluation(60, "点评", List.of(), "下一问",
                        InterviewTurn.KIND_QUESTION));

        service.answer(USER, SESSION, "我的回答内容");

        ArgumentCaptor<InterviewAgent.Context> captor =
                ArgumentCaptor.forClass(InterviewAgent.Context.class);
        verify(interviewAgent).reply(any(), captor.capture(), any(), any());

        assertNull(captor.getValue().resumeBrief(), "别人的简历不能被当作出题背景");
        assertTrue(captor.getValue().seedQuestions().isEmpty());
    }

    @Test
    @DisplayName("自己的简历会被转成背景摘要与参考问题")
    void usesOwnResumeAsContext() {
        InterviewSession s = session(USER);
        s.setResumeId(100L);
        when(sessionMapper.selectById(SESSION)).thenReturn(s);
        when(turnMapper.listBySession(SESSION)).thenReturn(List.of());
        when(turnMapper.maxSeq(SESSION)).thenReturn(0, 2);
        when(llmSettingService.requireConfigured(USER)).thenReturn(new LlmSetting());

        ResumeAnalysis mine = new ResumeAnalysis();
        mine.setId(100L);
        mine.setUserId(USER);
        mine.setProfileJson("""
                {"education":"武汉轻工大学 软件工程","targetRole":"Java 后端",
                 "skills":[{"name":"Java"},{"name":"Redis"}],
                 "projects":[{"name":"医疗问答系统","techStack":["FastAPI","ES"]}]}
                """);
        mine.setQuestionsJson("{\"groups\":[{\"questions\":[{\"question\":\"Redis 缓存穿透怎么处理？\"}]}]}");
        when(resumeAnalysisMapper.selectById(100L)).thenReturn(mine);

        when(interviewAgent.reply(any(), any(), any(), any()))
                .thenReturn(new InterviewAgent.Evaluation(60, "点评", List.of(), "下一问",
                        InterviewTurn.KIND_QUESTION));

        service.answer(USER, SESSION, "我的回答内容");

        ArgumentCaptor<InterviewAgent.Context> captor =
                ArgumentCaptor.forClass(InterviewAgent.Context.class);
        verify(interviewAgent).reply(any(), captor.capture(), any(), any());

        String brief = captor.getValue().resumeBrief();
        assertTrue(brief != null && brief.contains("Java"), "摘要要带上技能，实际：" + brief);
        assertTrue(brief.contains("Redis"), "摘要要带上技能，实际：" + brief);
        assertEquals(List.of("Redis 缓存穿透怎么处理？"), captor.getValue().seedQuestions(),
                "简历里预测的问题要作为出题方向传给面试官");
    }

    private static InterviewSession session(long userId) {
        InterviewSession s = new InterviewSession();
        s.setId(SESSION);
        s.setUserId(userId);
        s.setRole("Java 后端开发实习生");
        s.setLevel("junior");
        s.setType("technical");
        s.setStatus(InterviewSession.STATUS_RUNNING);
        s.setMaxRounds(5);
        return s;
    }
}
