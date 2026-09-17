package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次简历分析的结果。rawText 保留抽取出的原文，便于复现与排查。
 */
@Data
@TableName("resume_analysis")
public class ResumeAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String fileName;

    /** 从 PDF/Word 抽取出的纯文本 */
    private String rawText;

    /** LLM 第一步产出：结构化画像 JSON */
    private String profileJson;

    /** LLM 第二步产出：预测面试题 JSON（含题库命中项） */
    private String questionsJson;

    /** 一句话总评 */
    private String summary;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    private String errorMsg;

    /** 本次调用使用的模型名 */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
