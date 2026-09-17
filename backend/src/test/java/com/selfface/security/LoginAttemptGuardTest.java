package com.selfface.security;

import com.selfface.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防爆破的价值全在边界上：差一次不锁、多一次才锁、换个大小写不能绕过。
 */
class LoginAttemptGuardTest {

    private LoginAttemptGuard guard;

    @BeforeEach
    void setUp() {
        guard = new LoginAttemptGuard();
    }

    @Test
    @DisplayName("连续失败达到上限后锁定，并返回 429")
    void locksAfterMaxFailures() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.onFailure("alice");
        }

        BizException ex = assertThrows(BizException.class, () -> guard.checkAllowed("alice"));
        assertEquals(429, ex.getCode());
        assertTrue(ex.getMessage().contains("锁定"));
    }

    @Test
    @DisplayName("差一次不锁定")
    void doesNotLockBelowThreshold() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) {
            guard.onFailure("bob");
        }

        assertDoesNotThrow(() -> guard.checkAllowed("bob"));
    }

    @Test
    @DisplayName("登录成功后计数清零，不会累积到锁定")
    void successResetsCounter() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) {
            guard.onFailure("carol");
        }
        guard.onSuccess("carol");
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) {
            guard.onFailure("carol");
        }

        assertDoesNotThrow(() -> guard.checkAllowed("carol"), "清零后重新计数，不该锁定");
    }

    @Test
    @DisplayName("换大小写不能绕过锁定")
    void usernameIsCaseInsensitive() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.onFailure("Dave");
        }

        assertThrows(BizException.class, () -> guard.checkAllowed("dave"));
        assertThrows(BizException.class, () -> guard.checkAllowed("  DAVE  "));
    }

    @Test
    @DisplayName("账号之间互不影响")
    void countersArePerAccount() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            guard.onFailure("eve");
        }

        assertThrows(BizException.class, () -> guard.checkAllowed("eve"));
        assertDoesNotThrow(() -> guard.checkAllowed("frank"));
    }

    @Test
    @DisplayName("未失败过的账号不产生记录，避免内存被无用键撑大")
    void cleanAccountLeavesNoRecord() {
        guard.checkAllowed("grace");
        guard.onSuccess("grace");

        assertEquals(0, guard.trackedAccounts());
    }
}
