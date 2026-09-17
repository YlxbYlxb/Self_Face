package com.selfface.security;

import com.selfface.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败次数守卫。
 *
 * <p>IP 维度的限流（{@link RateLimitInterceptor}）挡的是「同一台机器狂试」，
 * 挡不住「一批机器各试几次同一个账号」，所以账号维度还要单独计数。
 *
 * <p>状态放在堆内：单实例部署足够；将来多实例需要换成 Redis，
 * 否则每台机器各算一份，实际可尝试次数会翻倍。
 */
@Slf4j
@Component
public class LoginAttemptGuard {

    /** 连续失败达到该次数即锁定 */
    static final int MAX_FAILURES = 5;
    /** 锁定时长 */
    static final long LOCK_MILLIS = 15 * 60_000L;
    /** 失败计数的有效窗口：超过这个时间没再失败，就从头计数 */
    static final long FAILURE_WINDOW_MILLIS = 15 * 60_000L;
    /** 追踪的账号上限，防止用随机用户名把内存刷爆 */
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static final class Attempt {
        int failures;
        long windowStart;
        long lockedUntil;
    }

    /**
     * 登录前调用。处于锁定期则直接拒绝，不再做密码校验。
     */
    public void checkAllowed(String username) {
        Attempt attempt = attempts.get(key(username));
        if (attempt == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (attempt.lockedUntil > now) {
            long seconds = (attempt.lockedUntil - now) / 1000;
            throw new BizException(429,
                    "密码错误次数过多，账号已临时锁定，请 " + humanize(seconds) + "后再试");
        }
        if (attempt.lockedUntil > 0) {
            // 锁定期已过，从零开始重新计数
            attempts.remove(key(username));
        }
    }

    public void onSuccess(String username) {
        attempts.remove(key(username));
    }

    /**
     * 记录一次失败。用户名不存在时也要记 —— 否则可以靠「是否被锁定」
     * 反推出账号是否存在。
     */
    public void onFailure(String username) {
        if (attempts.size() >= MAX_TRACKED) {
            purgeExpired();
            if (attempts.size() >= MAX_TRACKED) {
                // 极端情况（海量随机用户名）：放弃账号维度计数，交给 IP 限流兜底
                log.warn("登录失败记录已达上限 {}，本次跳过账号维度计数", MAX_TRACKED);
                return;
            }
        }

        attempts.compute(key(username), (k, existing) -> {
            long now = System.currentTimeMillis();
            Attempt attempt = existing;
            if (attempt == null || now - attempt.windowStart > FAILURE_WINDOW_MILLIS) {
                attempt = new Attempt();
                attempt.windowStart = now;
            }
            attempt.failures++;
            if (attempt.failures >= MAX_FAILURES) {
                attempt.lockedUntil = now + LOCK_MILLIS;
                log.warn("账号 {} 连续失败 {} 次，锁定 {} 分钟", k, attempt.failures, LOCK_MILLIS / 60_000);
            }
            return attempt;
        });
    }

    /** 仅供测试与排障使用 */
    public int trackedAccounts() {
        return attempts.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> {
            Attempt attempt = e.getValue();
            if (attempt.lockedUntil > now) {
                return false;
            }
            return now - attempt.windowStart > FAILURE_WINDOW_MILLIS;
        });
    }

    /**
     * 用户名在库里是不区分大小写的（utf8mb4_unicode_ci），
     * 计数键也必须归一化，否则大小写变体能绕过锁定。
     */
    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String humanize(long seconds) {
        if (seconds >= 60) {
            return ((seconds + 59) / 60) + " 分钟";
        }
        return Math.max(1, seconds) + " 秒";
    }
}
