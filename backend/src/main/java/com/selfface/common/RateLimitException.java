package com.selfface.common;

/**
 * 触发限流时抛出，由 {@link GlobalExceptionHandler} 统一转成 429。
 * 单独成类是为了把「请求太频繁」和「业务校验失败」区分开：
 * 前者前端应提示稍后重试，后者需要用户改输入。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
