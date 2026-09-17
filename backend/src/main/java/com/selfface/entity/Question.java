package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("question")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long categoryId;

    /** 题干 */
    private String title;

    /** 参考答案，Markdown */
    private String answer;

    /** 1 简单 / 2 中等 / 3 困难 */
    private Integer difficulty;

    /** 逗号分隔的标签，如「HashMap,红黑树,扩容」 */
    private String tags;

    /** 高频考点标记，1 为高频，参与每日组卷加权 */
    private Integer hot;

    private LocalDateTime createdAt;
}
