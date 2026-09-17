package com.selfface.llm;

/**
 * @param content          模型输出的正文
 * @param model            实际使用的模型名
 * @param promptTokens     输入 token 数，服务端未返回时为 0
 * @param completionTokens 输出 token 数，服务端未返回时为 0
 */
public record LlmResponse(String content, String model, int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
