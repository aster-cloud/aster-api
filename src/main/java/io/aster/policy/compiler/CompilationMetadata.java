package io.aster.policy.compiler;

/**
 * 编译产物的元信息，主要用于描述入口函数签名与参数/返回值结构。
 */
public final class CompilationMetadata {

    private final String functionSignature;
    private final String parameterSchema;
    private final String returnType;

    public CompilationMetadata(String functionSignature, String parameterSchema, String returnType) {
        this.functionSignature = functionSignature;
        this.parameterSchema = parameterSchema;
        this.returnType = returnType;
    }

    /**
     * 创建一个空的元信息占位对象，方便在暂无元数据时返回默认值。
     */
    public static CompilationMetadata empty() {
        return new CompilationMetadata(null, null, null);
    }

    public String getFunctionSignature() {
        return functionSignature;
    }

    public String getParameterSchema() {
        return parameterSchema;
    }

    public String getReturnType() {
        return returnType;
    }
}
