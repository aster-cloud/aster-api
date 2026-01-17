package io.aster.policy.graphql.converter;

import java.util.Map;

/**
 * GraphQL 领域转换器接口
 * 负责 GraphQL 输入/输出与 Aster 策略上下文之间的转换
 *
 * @param <TInput> GraphQL 输入类型（可以是单个对象或包装类）
 * @param <TOutput> GraphQL 输出类型
 */
public interface PolicyGraphQLConverter<TInput, TOutput> {
    /**
     * 将 GraphQL 输入转换为 Aster 策略执行上下文
     *
     * @param gqlInput GraphQL 输入对象
     * @param tenantId 租户ID
     * @return Aster 策略执行上下文（Map 形式）
     */
    Map<String, Object> toAsterContext(TInput gqlInput, String tenantId);

    /**
     * 将 Aster 策略执行结果转换为 GraphQL 输出对象
     *
     * @param asterResult Aster 策略执行结果（通常是 Map 或简单类型）
     * @return GraphQL 输出对象
     */
    TOutput toGraphQLResponse(Object asterResult);

    /**
     * 返回领域标识符
     *
     * @return 领域名称，如 "loans", "life-insurance" 等
     */
    String getDomain();
}

