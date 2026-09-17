package com.selfface.service;

import com.selfface.entity.LlmSetting;
import com.selfface.llm.LlmClient;
import com.selfface.mapper.LlmSettingMapper;
import com.selfface.security.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设置页最容易被改坏的两处：
 * 一是改模型时把已填的 Key 清掉，二是把回传的掩码串当成新 Key 存回去。
 * 引入加密后又多了一处 —— 掩码必须基于明文算，否则会把密文片段漏出去。
 */
@ExtendWith(MockitoExtension.class)
class LlmSettingServiceTest {

    private static final String ENC_PREFIX = "enc:v1:";

    @Mock
    private LlmSettingMapper llmSettingMapper;
    @Mock
    private LlmClient llmClient;
    @Mock
    private CryptoService cryptoService;

    private LlmSettingService service;
    private LlmSetting existing;

    @BeforeEach
    void setUp() {
        // 这里只需「可逆且带前缀」的哑加密，真实算法正确性由 CryptoServiceTest 覆盖
        lenient().when(cryptoService.encrypt(anyString())).thenAnswer(inv -> {
            String plain = inv.getArgument(0, String.class);
            return ENC_PREFIX + Base64.getEncoder()
                    .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        });
        lenient().when(cryptoService.decrypt(anyString())).thenAnswer(inv -> {
            String stored = inv.getArgument(0, String.class);
            if (stored == null || !stored.startsWith(ENC_PREFIX)) {
                return stored;
            }
            return new String(Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length())),
                    StandardCharsets.UTF_8);
        });

        service = new LlmSettingService(llmSettingMapper, llmClient, cryptoService);
        existing = new LlmSetting();
        existing.setId(1L);
        existing.setUserId(5L);
        existing.setApiKey("sk-original-key-value");
        existing.setBaseUrl("https://api.deepseek.com/v1");
        existing.setModel("deepseek-chat");
        when(llmSettingMapper.selectOne(any())).thenReturn(existing);
        // 只读用例不会触发写，用 lenient 避免 Mockito 的严格校验把这些用例判失败
        // 指定类型：MyBatis-Plus 的 updateById 同时有 (T) 与 (Collection<T>) 两个重载
        lenient().when(llmSettingMapper.updateById(any(LlmSetting.class))).thenReturn(1);
    }

    @Test
    @DisplayName("只改模型时传空 Key，原 Key 保持不变")
    void blankKeyKeepsExisting() {
        service.save(5L, null, "", "deepseek-reasoner", null, null);

        assertEquals("sk-original-key-value", existing.getApiKey());
        assertEquals("deepseek-reasoner", existing.getModel());
        verify(llmSettingMapper).updateById(existing);
    }

    @Test
    @DisplayName("回传的掩码串不会被当成新 Key 存回去")
    void maskedKeyDoesNotOverwrite() {
        service.save(5L, null, "sk-o****alue", null, null, null);

        assertEquals("sk-original-key-value", existing.getApiKey());
    }

    @Test
    @DisplayName("填入真实 Key 时加密后落库，且不残留明文")
    void realKeyIsEncryptedBeforePersist() {
        service.save(5L, null, "  sk-brand-new-key  ", null, null, null);

        String stored = existing.getApiKey();
        assertTrue(stored.startsWith(ENC_PREFIX), "落库的应当是密文，实际为：" + stored);
        assertFalse(stored.contains("sk-brand-new-key"), "密文里不应出现明文片段");
        assertEquals("sk-brand-new-key", cryptoService.decrypt(stored), "去掉首尾空白后加密保存");
    }

    @Test
    @DisplayName("超范围的温度和超时会被夹到安全区间")
    void clampsTemperatureAndTimeout() {
        service.save(5L, null, null, null, 9.9, 5);

        assertEquals(2.0, existing.getTemperature());
        assertEquals(20, existing.getTimeoutSeconds(), "超时下限 20 秒，否则模型来不及回话");
    }

    @Test
    @DisplayName("对外视图只回传掩码，不回传明文 Key")
    void viewNeverExposesPlainKey() {
        LlmSettingService.View view = service.get(5L);

        assertTrue(view.configured());
        assertEquals("sk-o****alue", view.maskedKey());
        assertFalse(view.maskedKey().contains("riginal"));
    }

    @Test
    @DisplayName("库里存密文时，掩码基于明文计算而不是切密文")
    void maskedKeyIsComputedFromPlainText() {
        existing.setApiKey(cryptoService.encrypt("sk-secret-1234567890"));

        LlmSettingService.View view = service.get(5L);

        assertEquals("sk-s****7890", view.maskedKey());
        assertFalse(view.maskedKey().contains(ENC_PREFIX), "掩码里不能出现密文前缀");
    }

    @Test
    @DisplayName("取用配置时返回明文副本，且不改动原实体")
    void requireConfiguredReturnsPlainCopy() {
        existing.setApiKey(cryptoService.encrypt("sk-real-key-abcdefgh"));

        LlmSetting usable = service.requireConfigured(5L);

        assertEquals("sk-real-key-abcdefgh", usable.getApiKey());
        assertTrue(existing.getApiKey().startsWith(ENC_PREFIX),
                "原实体必须保持密文，避免顺手 updateById 把明文写回库");
    }
}
