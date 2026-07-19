package io.aster.policy.api.model;

import java.util.List;

/**
 * 决策追踪信息，记录策略执行过程中的规则匹配路径
 *
 * 用于决策可解释性：显示哪条规则被触发、为什么返回特定结果。
 */
public record DecisionTrace(
    /** 被执行的模块名 */
    String moduleName,
    /** 被执行的函数名 */
    String functionName,
    /** 规则匹配步骤列表 */
    List<TraceStep> steps,
    /** 最终结果 */
    Object finalResult,
    /** 执行耗时（毫秒） */
    long executionTimeMs
) {
    /**
     * 单个追踪步骤
     */
    public record TraceStep(
        /** 步骤序号（从 1 开始） */
        int sequence,
        /** 规则/表达式描述 */
        String expression,
        /** 评估结果 */
        Object result,
        /** 是否为最终匹配的分支 */
        boolean matched,
        /** 嵌套子步骤（用于 if/else 或 match 分支） */
        List<TraceStep> children
    ) {}
}
