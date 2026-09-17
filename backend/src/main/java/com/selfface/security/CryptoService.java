package com.selfface.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 对称加密工具，用于把用户的 LLM API Key 加密后落库。
 *
 * <p>设计要点：
 * <ul>
 *   <li>算法 AES-256-GCM，带认证标签，能发现密文被篡改；</li>
 *   <li>每次加密随机生成 12 字节 IV，相同明文两次加密结果不同；</li>
 *   <li>密文带 {@code enc:v1:} 前缀，用于识别「已加密」；</li>
 *   <li>{@link #decrypt(String)} 遇到不带前缀的值会原样返回，
 *       因此历史遗留的明文 Key 无需数据迁移即可继续使用（下次保存时自动加密）。</li>
 * </ul>
 *
 * <p>密钥来自 {@code APP_CRYPTO_KEY} 环境变量。生产环境未显式配置时会拒绝启动，
 * 避免用了内置开发密钥却浑然不知。
 */
@Slf4j
@Component
public class CryptoService {

    /** 密文前缀，同时充当版本号，将来换算法可升到 v2 */
    private static final String PREFIX = "enc:v1:";

    /** 仅用于本地开发的兜底密钥；生产环境必须覆盖 */
    static final String DEV_KEY = "selfface-dev-crypto-key-please-change-2026";

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.crypto.key:" + DEV_KEY + "}")
    private String keyMaterial;

    private final Environment environment;

    private SecretKeySpec key;

    public CryptoService(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        boolean usingDevKey = DEV_KEY.equals(keyMaterial);
        if (usingDevKey && isProd()) {
            throw new IllegalStateException(
                    "生产环境必须通过环境变量 APP_CRYPTO_KEY 指定加密密钥，"
                            + "不能使用内置的开发默认值。生成示例：openssl rand -base64 32");
        }
        if (usingDevKey) {
            log.warn("正在使用内置开发密钥加密 API Key —— 仅限本地使用，部署前请设置 APP_CRYPTO_KEY");
        }
        try {
            // 把任意长度的口令散列成固定 32 字节 AES 密钥
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    /** @return 已加密的值原样返回，空值原样返回 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密。不带 {@code enc:v1:} 前缀的值视为历史明文，直接返回，
     * 这样老数据不用迁移；下次用户保存配置时会被自动加密。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度异常");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 最常见原因：换了 APP_CRYPTO_KEY，导致旧密文解不开
            throw new CryptoException(
                    "API Key 解密失败。若你更换过 APP_CRYPTO_KEY，需要到「设置」页重新填写 Key。", e);
        }
    }

    /**
     * 解密类故障。消息是给用户看的，因此可以安全地透传到接口响应里，
     * 与「500 一律回固定文案」的区别就在这里。
     */
    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private boolean isProd() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}
