package io.aster.policy.compiler;

import java.util.Collections;
import java.util.List;

/**
 * 表示编译流程的结果，封装 Core JSON、元信息以及错误列表。
 */
public final class CompilationResult {

    private final boolean success;
    private final String coreJson;
    private final CompilationMetadata metadata;
    private final List<String> errors;

    private CompilationResult(boolean success, String coreJson, CompilationMetadata metadata, List<String> errors) {
        this.success = success;
        this.coreJson = coreJson;
        this.metadata = metadata;
        this.errors = errors;
    }

    /**
     * 创建成功结果，默认携带空错误列表。
     */
    public static CompilationResult success(String coreJson, CompilationMetadata metadata) {
        CompilationMetadata resolvedMetadata = metadata != null ? metadata : CompilationMetadata.empty();
        return new CompilationResult(true, coreJson, resolvedMetadata, List.of());
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
        List<String> safeErrors = errors == null || errors.isEmpty()
            ? List.of("编译失败但未提供错误详情")
            : List.copyOf(errors);
        return new CompilationResult(false, null, null, safeErrors);
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
