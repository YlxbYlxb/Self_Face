package com.selfface.common;

/**
 * 统一响应体。code = 0 表示成功，非 0 为业务错误码。
 */
public record R<T>(int code, String msg, T data) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static R<Void> ok() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(400, msg, null);
    }
}
