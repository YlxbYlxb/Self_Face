package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次作答的明细记录。同一道题可以有多条，用于回看自己的表述有没有进步。
 */
@Data
@TableName("practice_record")
public class PracticeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long questionId;

    /** 1 不会 / 2 模糊 / 3 掌握 */
    private Integer mastery;

    /** 用户自己口述的答案，可为空 */
    private String answerText;

    /** 本次作答耗时（秒） */
    private Integer costSeconds;

    private LocalDateTime createdAt;
}
