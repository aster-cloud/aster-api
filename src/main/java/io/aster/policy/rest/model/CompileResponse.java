package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * REST API 响应：CNL 编译结果，字段与 cloud {@code PolicyCompileResponse} 对齐。
 *
 * {@code success} 为 false 时携带 {@code diagnostics}（含 1-based 行列）+
 * {@code error}（首条消息）；成功时携带 {@code module} 概要。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompileResponse(
    @JsonProperty("success")
    boolean success,

    @JsonProperty("module")
    ModuleInfo module,

    @JsonProperty("diagnostics")
    List<CompileDiagnostic> diagnostics,

    @JsonProperty("error")
    String error
) {
    /** 编译成功的模块概要，对齐 cloud 契约 {module:{name,functions[],types[]}}。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModuleInfo(
        @JsonProperty("name") String name,
        @JsonProperty("functions") List<String> functions,
        @JsonProperty("types") List<String> types
    ) {}

    public static CompileResponse ok(ModuleInfo module) {
        return new CompileResponse(true, module, List.of(), null);
    }

    public static CompileResponse fail(List<CompileDiagnostic> diagnostics, String error) {
        return new CompileResponse(false, null, diagnostics, error);
    }
}
