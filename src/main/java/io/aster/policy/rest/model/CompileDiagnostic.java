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
    String code,

    /** P0-C 稳定性诊断的机器可读特性标识（如 workflow/pii；非稳定诊断为 null）。 */
    @JsonProperty("featureId")
    String featureId,

    /** P0-C：strict surface 是否因此诊断阻断（severity 恒 warning，blocking 表达是否拒）。 */
    @JsonProperty("blocking")
    boolean blocking
) {
    /** 由 1-based 行列构造 error 级诊断（end==start，ANTLR 无终点信息）。 */
    public static CompileDiagnostic error(int line, int column, String message) {
        return new CompileDiagnostic("error", message, line, column, line, column, null, null, false);
    }

    /**
     * 从编译层诊断映射（保留 severity/code/end/featureId/blocking——W600 稳定性 warning 须带
     * code=W600 + featureId + blocking，前端 Monaco 才能显黄标 + 表达 strict 是否拒，ADR 0031）。
     */
    public static CompileDiagnostic from(io.aster.policy.compiler.CompilationResult.Diagnostic d) {
        return new CompileDiagnostic(
            d.severity() == null ? "error" : d.severity(),
            d.message(),
            d.line(), d.column(), d.endLine(), d.endColumn(),
            d.code(), d.featureId(), d.blocking());
    }
}
