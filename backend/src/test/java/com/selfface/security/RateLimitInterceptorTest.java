package com.selfface.security;

import com.selfface.common.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    private static final int GENERAL_LIMIT = 3;
    private static final int AUTH_LIMIT = 2;
    private static final int LLM_LIMIT = 1;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        ReflectionTestUtils.setField(interceptor, "generalPerMinute", GENERAL_LIMIT);
        ReflectionTestUtils.setField(interceptor, "authPerMinute", AUTH_LIMIT);
        ReflectionTestUtils.setField(interceptor, "llmPerMinute", LLM_LIMIT);
    }

    private HttpServletRequest request(String uri, String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getRequestURI()).thenReturn(uri);
        lenient().when(request.getHeader("X-Real-IP")).thenReturn(ip);
        return request;
    }

    private boolean pass(HttpServletRequest request) {
        return interceptor.preHandle(request, null, new Object());
    }

    @Test
    @DisplayName("普通接口超过阈值后拒绝")
    void blocksAfterGeneralLimit() {
        for (int i = 0; i < GENERAL_LIMIT; i++) {
            assertTrue(pass(request("/api/questions", "1.1.1.1")));
        }

        assertThrows(RateLimitException.class, () -> pass(request("/api/questions", "1.1.1.1")));
    }

    @Test
    @DisplayName("不同 IP 各算一份，不会互相牵连")
    void countsPerIp() {
        for (int i = 0; i < GENERAL_LIMIT; i++) {
            pass(request("/api/questions", "1.1.1.1"));
        }

        assertTrue(pass(request("/api/questions", "2.2.2.2")));
    }

    @Test
    @DisplayName("登录接口用更严的档位")
    void loginUsesStricterLimit() {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            pass(request("/api/auth/login", "3.3.3.3"));
        }

        assertThrows(RateLimitException.class, () -> pass(request("/api/auth/login", "3.3.3.3")));
    }

    @Test
    @DisplayName("同一 IP 打满登录档位，不影响它调普通接口")
    void bucketsAreIndependent() {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            pass(request("/api/auth/login", "4.4.4.4"));
        }

        assertTrue(pass(request("/api/questions", "4.4.4.4")),
                "登录被限不该连带普通请求一起挂掉");
    }

    @Test
    @DisplayName("大模型接口单独收紧")
    void llmEndpointsUseOwnBucket() {
        pass(request("/api/llm/test", "5.5.5.5"));

        assertThrows(RateLimitException.class, () -> pass(request("/api/llm/test", "5.5.5.5")));
        // 换个路径仍属同一档位
        assertThrows(RateLimitException.class, () -> pass(request("/api/resume/analyze", "5.5.5.5")));
    }

    @Test
    @DisplayName("健康检查不限流，否则会把自己的探针拦掉")
    void healthCheckIsExempt() {
        for (int i = 0; i < 100; i++) {
            assertTrue(pass(request("/api/health", "6.6.6.6")));
        }
    }

    @Test
    @DisplayName("非接口路径不参与限流")
    void nonApiPathsAreIgnored() {
        for (int i = 0; i < 100; i++) {
            assertTrue(pass(request("/assets/index.js", "7.7.7.7")));
        }
    }

    @Test
    @DisplayName("开关关闭时全部放行")
    void disabledMeansNoLimit() {
        ReflectionTestUtils.setField(interceptor, "enabled", false);

        for (int i = 0; i < 50; i++) {
            assertTrue(pass(request("/api/auth/login", "8.8.8.8")));
        }
    }

    @Test
    @DisplayName("没有 X-Real-IP 时退回 X-Forwarded-For 的最左值")
    void fallsBackToForwardedFor() {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getRequestURI()).thenReturn("/api/auth/login");
            when(req.getHeader("X-Real-IP")).thenReturn(null);
            when(req.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 10.0.0.1");
            pass(req);
        }

        HttpServletRequest third = mock(HttpServletRequest.class);
        when(third.getRequestURI()).thenReturn("/api/auth/login");
        when(third.getHeader("X-Real-IP")).thenReturn(null);
        when(third.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9");
        assertThrows(RateLimitException.class, () -> pass(third),
                "同一个客户端地址应当命中同一份计数");
    }

    @Test
    @DisplayName("限流异常带可读提示，前端能直接展示")
    void exceptionMessageIsUserFacing() {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            pass(request("/api/auth/login", "11.11.11.11"));
        }

        RateLimitException ex = assertThrows(RateLimitException.class,
                () -> pass(request("/api/auth/login", "11.11.11.11")));
        assertDoesNotThrow(() -> ex.getMessage());
        assertTrue(ex.getMessage().contains("频繁"));
    }
}
