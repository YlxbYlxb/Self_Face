package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模拟面试里的一轮对话。
 *
 * <p>面试官的提问（question / follow_up）与候选人的回答（answer）都记在这里。
 * score 与 comment 挂在面试官那一轮上，表示<b>对上一次回答</b>的评价——
 * 这样前端可以「回答 → 看到点评 → 看到下一问」地顺序渲染。
 */
@Data
@TableName("interview_turn")
public class InterviewTurn {

    public static final String ROLE_INTERVIEWER = "interviewer";
    public static final String ROLE_CANDIDATE = "candidate";

    public static final String KIND_QUESTION = "question";
    public static final String KIND_FOLLOW_UP = "follow_up";
    public static final String KIND_ANSWER = "answer";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Integer seq;

    /** interviewer / candidate */
    private String role;

    /** question / follow_up / answer */
    private String kind;

    private String content;

    /** 对上一次回答的评分 0-100 */
    private Integer score;

    /** 对上一次回答的点评 */
    private String comment;

    private LocalDateTime createdAt;
}
