package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 展示名，如「Java 并发」 */
    private String name;

    /** 唯一编码，如 java-concurrency */
    private String code;

    private String description;

    private Integer sortOrder;
}
