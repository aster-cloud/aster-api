package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aster.policy.api.schema.ParameterSchemaExtractor;

import java.util.List;

/**
 * REST API响应：策略参数模式
 *
 * 包含函数参数的结构化模式信息，用于动态表单生成。
 */
public record SchemaResponse(
    @JsonProperty("success")
    boolean success,

    @JsonProperty("moduleName")
    String moduleName,

    @JsonProperty("functionName")
    String functionName,

    @JsonProperty("parameters")
    List<ParameterInfo> parameters,

    @JsonProperty("error")
    String error
) {
    /**
     * 参数信息（用于 JSON 序列化）
     */
    public record ParameterInfo(
        @JsonProperty("name")
        String name,

        @JsonProperty("type")
        String type,

        @JsonProperty("typeKind")
        String typeKind,

        @JsonProperty("optional")
        boolean optional,

        @JsonProperty("position")
        int position,

        @JsonProperty("fields")
        List<FieldInfo> fields
    ) {
        public static ParameterInfo from(ParameterSchemaExtractor.ParameterInfo p) {
            List<FieldInfo> fields = p.fields() != null
                ? p.fields().stream().map(FieldInfo::from).toList()
                : List.of();
            return new ParameterInfo(
                p.name(),
                p.type(),
                p.typeKind().name().toLowerCase(),
                p.optional(),
                p.position(),
                fields
            );
        }
    }

    /**
     * 字段信息（用于结构体类型）
     */
    public record FieldInfo(
        @JsonProperty("name")
        String name,

        @JsonProperty("type")
        String type,

        @JsonProperty("typeKind")
        String typeKind
    ) {
        public static FieldInfo from(ParameterSchemaExtractor.FieldInfo f) {
            return new FieldInfo(
                f.name(),
                f.type(),
                f.typeKind().name().toLowerCase()
            );
        }
    }

    /**
     * 创建成功响应
     */
    public static SchemaResponse success(ParameterSchemaExtractor.SchemaResult result) {
        List<ParameterInfo> params = result.parameters().stream()
            .map(ParameterInfo::from)
            .toList();
        return new SchemaResponse(
            true,
            result.moduleName(),
            result.functionName(),
            params,
            null
        );
    }

    /**
     * 创建错误响应
     */
    public static SchemaResponse error(String errorMessage) {
        return new SchemaResponse(false, null, null, List.of(), errorMessage);
    }
}
