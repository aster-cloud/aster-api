package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 编译诊断项，字段与前端 Monaco 契约对齐（1-based 行列）。
 *
 * 对应 cloud {@code PolicyDiagnostic}：severity 为小写字符串枚举
 * （error/warning/info/hint），行列 1-based。ANTLR 只给起点，故 end == start。
 */
public record CompileDiagnostic(
    @JsonProperty("severity")
    String severity,

    @JsonProperty("message")
    String message,

    @JsonProperty("startLine")
    int startLine,

    @JsonProperty("startColumn")
    int startColumn,

    @JsonProperty("endLine")
    int endLine,

    @JsonProperty("endColumn")
    int endColumn,

    @JsonProperty("code")
    String code
) {
    /** 由 1-based 行列构造 error 级诊断（end==start，ANTLR 无终点信息）。 */
    public static CompileDiagnostic error(int line, int column, String message) {
        return new CompileDiagnostic("error", message, line, column, line, column, null);
    }
}
