package io.aster.policy.compiler;

import java.util.Collections;
import java.util.List;

/**
 * 表示编译流程的结果，封装 Core JSON、元信息以及错误列表。
 */
public final class CompilationResult {

    /**
     * 结构化诊断（含 1-based 行列 + 消息），供编译端点透传给前端精确标错。
     * 语法错误路径由 CnlErrorListener 填充；语义/其它失败可能只有 errors 字符串
     * 而无结构化 diagnostics（此时端点回退用 errors 生成行列=1 的兜底诊断）。
     */
    public record Diagnostic(int line, int column, String message) {}

    private final boolean success;
    private final String coreJson;
    private final CompilationMetadata metadata;
    private final List<String> errors;
    private final List<Diagnostic> diagnostics;

    private CompilationResult(boolean success, String coreJson, CompilationMetadata metadata,
                             List<String> errors, List<Diagnostic> diagnostics) {
        this.success = success;
        this.coreJson = coreJson;
        this.metadata = metadata;
        this.errors = errors;
        this.diagnostics = diagnostics == null ? List.of() : diagnostics;
    }

    /**
     * 创建成功结果，默认携带空错误列表。
     */
    public static CompilationResult success(String coreJson, CompilationMetadata metadata) {
        CompilationMetadata resolvedMetadata = metadata != null ? metadata : CompilationMetadata.empty();
        return new CompilationResult(true, coreJson, resolvedMetadata, List.of(), List.of());
    }

    /**
     * 创建失败结果（单条错误信息）。
     */
    public static CompilationResult failure(String error) {
        String message = (error == null || error.isBlank()) ? "编译失败" : error;
        return failure(Collections.singletonList(message));
    }

    /**
     * 创建失败结果（多条错误信息）。
     */
    public static CompilationResult failure(List<String> errors) {
        return failure(errors, List.of());
    }

    /**
     * 创建失败结果（多条错误信息 + 结构化诊断）。
     */
    public static CompilationResult failure(List<String> errors, List<Diagnostic> diagnostics) {
        List<String> safeErrors = errors == null || errors.isEmpty()
            ? List.of("编译失败但未提供错误详情")
            : List.copyOf(errors);
        return new CompilationResult(false, null, null, safeErrors,
            diagnostics == null ? List.of() : List.copyOf(diagnostics));
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCoreJson() {
        return coreJson;
    }

    public CompilationMetadata getMetadata() {
        return metadata;
    }

    public List<String> getErrors() {
        return errors;
    }

    /** 结构化诊断（含 1-based 行列）；可能为空（如无位置信息的语义失败）。 */
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    @Override
    public String toString() {
        return "CompilationResult{" +
            "success=" + success +
            ", coreJsonLength=" + (coreJson == null ? 0 : coreJson.length()) +
            ", metadata=" + metadata +
            ", errors=" + errors +
            '}';
    }
}
