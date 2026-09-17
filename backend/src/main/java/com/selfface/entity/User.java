package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 散列，序列化时永不外泄 */
    @JsonIgnore
    private String password;

    private String nickname;

    private String email;

    /** 求职目标城市，逗号分隔，用于简历分析时提示地域偏好 */
    private String targetCities;

    /** 求职方向，如「Java 后端开发实习生」 */
    private String targetPosition;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
