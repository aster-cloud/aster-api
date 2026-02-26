package io.aster.llm.api.dto;

/**
 * 代码补全响应
 *
 * @param completion 补全文本
 * @param model      实际使用的模型
 */
public record CompleteResponse(
    String completion,
    String model
) {}
