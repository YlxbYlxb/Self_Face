package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次模拟面试会话。
 *
 * <p>题源可以是 {@code resumeId} 指向的简历分析（复用已经解析好的画像与预测问题），
 * 也可以只用 role / level / type 三个字段让模型自由出题。
 */
@Data
@TableName("interview_session")
public class InterviewSession {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_FINISHED = "FINISHED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long resumeId;

    private String role;

    /** junior / mid / senior */
    private String level;

    /** technical / project / comprehensive */
    private String type;

    private String status;

    /** 计划的问答轮数 */
    private Integer maxRounds;

    /** 面试官总评得分 0-100 */
    private Integer overallScore;

    /** 最终报告 JSON */
    private String reportJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
