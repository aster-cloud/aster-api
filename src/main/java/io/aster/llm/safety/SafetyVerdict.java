package io.aster.llm.safety;

/**
 * 内容安全判定结果
 *
 * @param blocked  是否拒绝调用 LLM
 * @param ruleId   命中的规则 id（用于审计 / safetyFlags 入库；不向终端用户暴露）
 * @param message  返回给终端用户的简短说明（脱敏，不暴露规则细节）
 */
public record SafetyVerdict(boolean blocked, String ruleId, String message) {
    public static SafetyVerdict allow() {
        return new SafetyVerdict(false, null, null);
    }

    public static SafetyVerdict block(String ruleId, String message) {
        return new SafetyVerdict(true, ruleId, message);
    }
}
