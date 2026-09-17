package com.selfface.common;

/**
 * 简历分析任务的状态流转：PENDING → RUNNING → SUCCESS / FAILED。
 * 落库在 resume_analysis.status，前端靠轮询这个字段判断是否结束。
 */
public final class ResumeStatus {

    /** 已入库，等待后台线程取走 */
    public static final String PENDING = "PENDING";
    /** 正在调用大模型 */
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private ResumeStatus() {
    }
}
