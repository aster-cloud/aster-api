package io.aster.llm.model;

import java.util.List;

/**
 * CNL 编译校验结果
 *
 * @param ok          校验是否通过
 * @param errors      错误列表
 * @param moduleName  模块名（校验通过时）
 * @param functionName 函数名（校验通过时）
 */
public record ValidationResult(
    boolean ok,
    List<String> errors,
    String moduleName,
    String functionName
) {
    public static ValidationResult success(String moduleName, String functionName) {
        return new ValidationResult(true, List.of(), moduleName, functionName);
    }

    public static ValidationResult failure(List<String> errors) {
        return new ValidationResult(false, errors, null, null);
    }

    public static ValidationResult failure(String error) {
        return new ValidationResult(false, List.of(error), null, null);
    }

    public String errorsAsString() {
        return String.join("\n", errors);
    }
}
