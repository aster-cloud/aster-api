package io.aster.policy.rest.model;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * JSON 格式策略评估请求
 *
 * 支持直接传入 Core IR JSON 格式的策略进行评估，无需提前部署
 *
 * @param policy Core IR JSON 格式的策略定义
 * @param context 评估上下文参数（Map 或 Object 数组）
 */
public record JsonPolicyRequest(
    @NotNull(message = "policy 不能为空")
    String policy,

    @NotNull(message = "context 不能为空")
    Object context
) {
}
