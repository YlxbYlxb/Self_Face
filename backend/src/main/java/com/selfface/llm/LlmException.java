package com.selfface.llm;

/**
 * 调用大模型失败时抛出，message 直接面向用户展示，所以要写人话。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
