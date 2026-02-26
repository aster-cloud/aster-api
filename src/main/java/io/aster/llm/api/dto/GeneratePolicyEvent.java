package io.aster.llm.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 事件数据
 *
 * 事件类型：
 * - delta: 流式增量内容
 * - validation_error: 编译校验失败，附带错误详情
 * - final: 生成完成，包含最终完整代码
 * - error: 服务端错误
 *
 * @param type  事件类型
 * @param data  内容数据
 * @param error 错误信息（仅 error/validation_error 类型）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeneratePolicyEvent(
    String type,
    String data,
    String error
) {
    public static GeneratePolicyEvent delta(String content) {
        return new GeneratePolicyEvent("delta", content, null);
    }

    public static GeneratePolicyEvent validationError(String errorDetails) {
        return new GeneratePolicyEvent("validation_error", null, errorDetails);
    }

    public static GeneratePolicyEvent finalResult(String source) {
        return new GeneratePolicyEvent("final", source, null);
    }

    public static GeneratePolicyEvent error(String message) {
        return new GeneratePolicyEvent("error", null, message);
    }
}
