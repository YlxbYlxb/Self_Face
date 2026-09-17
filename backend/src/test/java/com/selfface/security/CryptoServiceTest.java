package com.selfface.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加密是「API Key 不明文入库」这条承诺的底座，坏了不会立刻报错，
 * 只会在某天泄露出去，所以这里覆盖得细一些。
 */
class CryptoServiceTest {

    private static final String KEY = "test-key-material-at-least-32-chars-long";

    private CryptoService newService(String keyMaterial, String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        if (activeProfiles.length > 0) {
            env.setActiveProfiles(activeProfiles);
        }
        CryptoService service = new CryptoService(env);
        ReflectionTestUtils.setField(service, "keyMaterial", keyMaterial);
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }

    @Test
    @DisplayName("加解密往返一致")
    void roundTrip() {
        CryptoService service = newService(KEY);

        String cipher = service.encrypt("sk-1234567890abcdef");

        assertTrue(service.isEncrypted(cipher));
        assertEquals("sk-1234567890abcdef", service.decrypt(cipher));
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（随机 IV）")
    void sameInputProducesDifferentCipherText() {
        CryptoService service = newService(KEY);

        String first = service.encrypt("sk-same-value");
        String second = service.encrypt("sk-same-value");

        assertNotEquals(first, second, "IV 复用会让攻击者看出两条密文对应同一明文");
        assertEquals(service.decrypt(first), service.decrypt(second));
    }

    @Test
    @DisplayName("密文带 enc:v1: 前缀，便于识别与将来升级算法")
    void cipherTextCarriesVersionPrefix() {
        CryptoService service = newService(KEY);

        assertTrue(service.encrypt("sk-x").startsWith("enc:v1:"));
        assertFalse(service.isEncrypted("sk-plain-value"));
    }

    @Test
    @DisplayName("历史明文能原样读出，不需要数据迁移")
    void plainTextFromLegacyDataIsReturnedAsIs() {
        CryptoService service = newService(KEY);

        assertEquals("sk-legacy-plain", service.decrypt("sk-legacy-plain"));
    }

    @Test
    @DisplayName("重复加密不会把密文套娃")
    void encryptingCipherTextIsNoOp() {
        CryptoService service = newService(KEY);

        String once = service.encrypt("sk-value");
        assertEquals(once, service.encrypt(once));
        assertEquals("sk-value", service.decrypt(service.encrypt(once)));
    }

    @Test
    @DisplayName("空值原样返回，不产生无意义的密文")
    void blankValuesPassThrough() {
        CryptoService service = newService(KEY);

        assertNull(service.encrypt(null));
        assertEquals("", service.encrypt(""));
        assertNull(service.decrypt(null));
    }

    @Test
    @DisplayName("密文被篡改时解密失败而不是返回脏数据")
    void tamperedCipherTextIsRejected() {
        CryptoService service = newService(KEY);
        String cipher = service.encrypt("sk-tamper-me");
        // 翻转最后一个字符，GCM 的认证标签会校验失败
        char last = cipher.charAt(cipher.length() - 1);
        String tampered = cipher.substring(0, cipher.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThrows(CryptoService.CryptoException.class, () -> service.decrypt(tampered));
    }

    @Test
    @DisplayName("换了密钥就解不开旧密文，并给出可操作的提示")
    void changingKeyBreaksOldCipherText() {
        String cipher = newService(KEY).encrypt("sk-rotate-me");
        CryptoService rotated = newService("another-key-material-also-32-chars-long");

        CryptoService.CryptoException ex =
                assertThrows(CryptoService.CryptoException.class, () -> rotated.decrypt(cipher));
        assertTrue(ex.getMessage().contains("重新填写"), "提示里要告诉用户怎么办");
    }

    @Test
    @DisplayName("生产环境用内置开发密钥时拒绝启动")
    void prodProfileRejectsDevKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newService(CryptoService.DEV_KEY, "prod"));

        assertTrue(ex.getMessage().contains("APP_CRYPTO_KEY"));
    }

    @Test
    @DisplayName("开发环境用内置密钥只告警，不阻断启动")
    void devProfileAllowsDevKey() {
        CryptoService service = newService(CryptoService.DEV_KEY);

        assertEquals("sk-dev", service.decrypt(service.encrypt("sk-dev")));
    }
}
