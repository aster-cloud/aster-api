package io.aster.llm.safety;

/**
 * 提示词注入 / 越狱检测器接口（可插拔）
 *
 * v1 默认实现：{@link RegexInjectionDetector}（regex-based，<10ms）
 * 将来可替换为小模型分类器，业务路径无需改动。
 */
public interface PromptInjectionDetector {

    /**
     * 在调用 LLM 前同步执行；命中即拒绝调用并写入 safetyFlags。
     */
    SafetyVerdict detect(String prompt);
}
