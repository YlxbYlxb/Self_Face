package com.selfface.security;

import com.selfface.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取当前登录用户 id 的快捷入口。未登录直接抛 401。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long userId)) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return userId;
    }
}
