package com.selfface.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.selfface.common.BizException;
import com.selfface.entity.LlmSetting;
import com.selfface.llm.LlmClient;
import com.selfface.llm.LlmResponse;
import com.selfface.mapper.LlmSettingMapper;
import com.selfface.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户的 LLM 配置读写。
 *
 * <p>API Key 在库里始终是 AES-GCM 密文；对外只回传掩码，对模型只传明文，
 * 三条路径分别由 {@link CryptoService} 统一处理，调用方不需要关心加解密。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSettingService {

    private final LlmSettingMapper llmSettingMapper;
    private final LlmClient llmClient;
    private final CryptoService cryptoService;

    /** 对外展示用的视图，永远不含明文 Key */
    public record View(String baseUrl, String model, Double temperature, Integer timeoutSeconds,
                       boolean configured, String maskedKey) {
    }

    public View get(Long userId) {
        LlmSetting s = find(userId);
        if (s == null) {
            return new View("", "", 0.3, 120, false, "");
        }
        boolean configured = s.getApiKey() != null && !s.getApiKey().isBlank()
                && s.getBaseUrl() != null && !s.getBaseUrl().isBlank()
                && s.getModel() != null && !s.getModel().isBlank();
        return new View(s.getBaseUrl(), s.getModel(), s.getTemperature(), s.getTimeoutSeconds(),
                configured, safeMask(s.getApiKey()));
    }

    @Transactional
    public View save(Long userId, String baseUrl, String apiKey, String model,
                     Double temperature, Integer timeoutSeconds) {
        LlmSetting s = find(userId);
        if (s == null) {
            s = new LlmSetting();
            s.setUserId(userId);
            s.setApiKey(apiKey == null ? null : cryptoService.encrypt(apiKey.trim()));
            s.setBaseUrl(baseUrl == null ? null : baseUrl.trim());
            s.setModel(model);
            s.setTemperature(temperature);
            s.setTimeoutSeconds(timeoutSeconds);
            s.setUpdatedAt(LocalDateTime.now());
            llmSettingMapper.insert(s);
            return get(userId);
        }

        if (baseUrl != null && !baseUrl.isBlank()) {
            s.setBaseUrl(baseUrl.trim());
        }
        // Key 传空表示「保持原有 Key 不变」，这样改模型时不必重新粘贴 Key
        if (apiKey != null && !apiKey.isBlank()) {
            String trimmed = apiKey.trim();
            // 页面回传的掩码串不是真 Key，遇到就跳过，避免把 sk-ab****yz 写进库
            if (!trimmed.contains("****")) {
                s.setApiKey(cryptoService.encrypt(trimmed));
            }
        }
        if (model != null && !model.isBlank()) {
            s.setModel(model.trim());
        }
        if (temperature != null) {
            s.setTemperature(Math.max(0.0, Math.min(2.0, temperature)));
        }
        if (timeoutSeconds != null) {
            s.setTimeoutSeconds(Math.max(20, Math.min(600, timeoutSeconds)));
        }
        s.setUpdatedAt(LocalDateTime.now());
        llmSettingMapper.updateById(s);
        return get(userId);
    }

    /**
     * 内部调用入口：拿到含明文 Key 的完整配置，未配置则抛业务异常。
     *
     * <p>返回的是副本，原始实体不被就地改写 —— 否则一旦有人顺手 {@code updateById}，
     * 就会把明文 Key 写回数据库。
     */
    public LlmSetting requireConfigured(Long userId) {
        LlmSetting s = find(userId);
        if (s == null || s.getApiKey() == null || s.getApiKey().isBlank()) {
            throw new BizException("还没有配置大模型 API Key。请到「设置」页填写后再使用 AI 功能。");
        }
        if (s.getBaseUrl() == null || s.getBaseUrl().isBlank()) {
            throw new BizException("还没有配置 Base URL，请到「设置」页填写。");
        }
        if (s.getModel() == null || s.getModel().isBlank()) {
            throw new BizException("还没有配置模型名，请到「设置」页填写。");
        }
        LlmSetting copy = new LlmSetting();
        BeanUtils.copyProperties(s, copy);
        copy.setApiKey(cryptoService.decrypt(s.getApiKey()));
        return copy;
    }

    /**
     * 连通性自检：发一句最短的请求，能回话就算通。
     */
    public String test(Long userId) {
        LlmSetting s = requireConfigured(userId);
        LlmResponse resp = llmClient.chat(s, "你是一个连通性测试助手。", "只回复两个字：正常", false);
        return "连接成功，模型 " + resp.model() + " 返回：" + resp.content().trim();
    }

    private LlmSetting find(Long userId) {
        return llmSettingMapper.selectOne(
                new LambdaQueryWrapper<LlmSetting>().eq(LlmSetting::getUserId, userId));
    }

    /**
     * 掩码必须基于明文计算。库里是密文，直接切前 4 后 4 位会把密文片段暴露出去。
     * 解密失败（多因更换过 APP_CRYPTO_KEY）时不抛异常，避免设置页整页打不开。
     */
    private String safeMask(String storedKey) {
        if (storedKey == null || storedKey.isBlank()) {
            return "";
        }
        try {
            return mask(cryptoService.decrypt(storedKey));
        } catch (CryptoService.CryptoException e) {
            log.warn("API Key 无法解密，设置页将提示重新填写：{}", e.getMessage());
            return "无法解密，请重新填写";
        }
    }

    private static String mask(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return "";
        }
        if (plainKey.length() <= 8) {
            return "****";
        }
        return plainKey.substring(0, 4) + "****" + plainKey.substring(plainKey.length() - 4);
    }
}
