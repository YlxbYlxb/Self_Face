package com.selfface.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自己的 LLM 配置（BYOK：Bring Your Own Key）。
 * 兼容 OpenAI 协议的服务都能接：OpenAI、DeepSeek、通义、Kimi、本地 Ollama 等。
 */
@Data
@TableName("llm_setting")
public class LlmSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 如 https://api.deepseek.com/v1 */
    private String baseUrl;

    /** 只写不读：接口永不回传明文，只回传掩码（掩码逻辑见 LlmSettingService） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    /** 如 deepseek-chat、gpt-4o-mini、qwen-plus */
    private String model;

    /** 0.0 - 2.0 */
    private Double temperature;

    /** 单次请求超时（秒） */
    private Integer timeoutSeconds;

    private LocalDateTime updatedAt;
}
