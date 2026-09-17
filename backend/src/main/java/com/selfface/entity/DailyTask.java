package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日题单：用户某一天被分配到的题目。唯一键 (user_id, task_date, question_id) 保证重复请求不会重复组卷。
 */
@Data
@TableName("daily_task")
public class DailyTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate taskDate;

    private Long questionId;

    /** 当天题单内的序号，从 1 开始 */
    private Integer seq;

    /** 0 未作答 / 1 不会 / 2 模糊 / 3 掌握 */
    private Integer mastery;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
