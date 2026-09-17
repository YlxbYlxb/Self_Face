package com.selfface.security;

import com.selfface.common.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 IP 的固定窗口限流。
 *
 * <p>分三档：登录注册最严（防爆破）、大模型次之（直接烧钱）、其余最宽松。
 * 计数放在堆内，单实例够用；多实例部署需要换成 Redis 才能全局生效。
 *
 * <p>为什么用固定窗口而不是滑动窗口：实现简单、内存恒定，
 * 代价是窗口边界处最坏情况可能放过约两倍流量，对本项目量级可以接受。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final long CLEANUP_INTERVAL_MILLIS = 5 * 60_000L;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.general-per-minute:240}")
    private int generalPerMinute;

    @Value("${app.rate-limit.auth-per-minute:10}")
    private int authPerMinute;

    @Value("${app.rate-limit.llm-per-minute:20}")
    private int llmPerMinute;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastCleanup = System.currentTimeMillis();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            return true;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        // 健康检查会被负载均衡高频调用，限流它只会误伤自己
        if ("/api/health".equals(path)) {
            return true;
        }

        Group group = groupOf(path);
        if (group.limit() <= 0) {
            return true;
        }

        String ip = clientIp(request);
        long minute = System.currentTimeMillis() / WINDOW_MILLIS;
        String key = group.name() + "|" + ip;

        Window window = windows.compute(key, (k, existing) ->
                (existing == null || existing.minute != minute) ? new Window(minute) : existing);
        int count = window.count.incrementAndGet();
        cleanupIfDue(minute);

        if (count > group.limit()) {
            log.warn("触发限流：ip={} path={} 档位={} 计数={}/{}",
                    ip, path, group.name(), count, group.limit());
            throw new RateLimitException("操作过于频繁，请稍后再试（每分钟最多 " + group.limit() + " 次）");
        }
        return true;
    }

    private record Group(String name, int limit) {
    }

    private Group groupOf(String path) {
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) {
            return new Group("auth", authPerMinute);
        }
        // 这几个接口都会真实调用用户的大模型（直接烧钱），单独收紧
        if (path.startsWith("/api/llm")
                || path.startsWith("/api/resume/analyze")
                || path.startsWith("/api/jd/analyze")
                || path.startsWith("/api/interview")) {
            return new Group("llm", llmPerMinute);
        }
        return new Group("general", generalPerMinute);
    }

    /**
     * 取真实客户端 IP。
     *
     * <p>优先 X-Real-IP：Nginx 用 proxy_set_header 覆盖写入，客户端伪造不了。
     * 其次 X-Forwarded-For 的最左值（开发期直连时可能没有前两个头，回落到 remoteAddr）。
     */
    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 惰性清理：不额外起定时线程，靠请求顺带把过期窗口删掉，
     * 否则长期运行后 Map 会随访问过的 IP 数无限增长。
     */
    private void cleanupIfDue(long currentMinute) {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_INTERVAL_MILLIS) {
            return;
        }
        lastCleanup = now;
        // 只留当前与上一分钟的窗口，更早的都已失效
        windows.entrySet().removeIf(e -> currentMinute - e.getValue().minute > 1);
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
