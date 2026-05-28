package io.aster.policy.api;

import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.aster.policy.api.cache.PolicyCacheManager;
import io.aster.policy.api.convert.PolicyTypeConverter;
import io.aster.policy.api.model.BatchEvaluationResult;
import io.aster.policy.api.model.BatchRequest;
import io.aster.policy.api.model.CompositionStep;
import io.aster.policy.api.model.EvaluationAttempt;
import io.aster.policy.api.model.ParameterInfo;
import io.aster.policy.api.model.PolicyCompositionResult;
import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.api.model.PolicyValidationResult;
import io.aster.policy.api.model.StepResult;
import io.aster.policy.compiler.CompilationMetadata;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import io.aster.policy.runtime.ExecutionResult;
import io.aster.policy.runtime.TrufflePolicyRuntime;
import io.aster.policy.tenant.TenantContext;
import io.aster.workflow.DeterminismContext;
import io.aster.workflow.PostgresWorkflowRuntime;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import org.jboss.logging.Logger;

/**
 * Policy evaluation service with caching support (Reactive)
 *
 * 该服务负责策略评估并使用Caffeine缓存来提升性能。
 * 使用Mutiny的Uni实现reactive编程模型。
 * 缓存key基于策略模块、函数名和输入参数的哈希值。
 */
@ApplicationScoped
public class PolicyEvaluationService {

    @Inject
    PolicyCacheManager policyCacheManager;

    @Inject
    PolicyTypeConverter policyTypeConverter;

    @Inject
    PostgresWorkflowRuntime workflowRuntime;

    @Inject
    PolicySourceRepository policySourceRepository;

    @Inject
    PolicyCompiler policyCompiler;

    @Inject
    TrufflePolicyRuntime trufflePolicyRuntime;

    @Inject
    io.aster.policy.cache.CompiledPolicyCache compiledPolicyCache;

    @Inject
    TenantContext tenantContext;

    @ConfigProperty(name = "aster.policy.dynamic-loading.enabled", defaultValue = "false")
    boolean dynamicLoadingEnabled;

    private static final Logger LOG = Logger.getLogger(PolicyEvaluationService.class);

    private static final java.util.List<String> HOT_POLICIES = java.util.List.of(
        "aster.insurance.life.generateLifeQuote",
        "aster.insurance.life.calculateRiskScore",
        "aster.insurance.auto.generateAutoQuote",
        "aster.healthcare.eligibility.checkServiceEligibility",
        "aster.healthcare.claims.processClaim",
        "aster.finance.loan.evaluateLoanEligibility",
        "aster.finance.creditcard.evaluateCreditCardApplication",
        "aster.finance.enterprise_lending.evaluateEnterpriseLoan",
        "aster.finance.personal_lending.evaluatePersonalLoan"
    );

    /**
     * 评估策略（带缓存，reactive版本，优化了反射性能）
     *
     * @param policyModule 策略模块名
     * @param policyFunction 策略函数名
     * @param context 上下文参数
     * @return Uni包装的评估结果
     */
    @WithSpan("policy.evaluate")
    public Uni<PolicyEvaluationResult> evaluatePolicy(
            @SpanAttribute("tenant.id") String tenantId,
            @SpanAttribute("policy.module") String policyModule,
            @SpanAttribute("policy.function") String policyFunction,
            Object[] context) {

        Object[] normalizedContext = normalizeContext(tenantId, context);

        // 动态加载模式下，需要在 worker pool 中获取 versionId 以避免阻塞 IO 线程
        // 使用 QuarkusTransaction.requiringNew() 确保在独立事务中执行，避免并发访问时的会话共享问题
        if (dynamicLoadingEnabled) {
            return Uni.createFrom().item(() -> QuarkusTransaction.requiringNew()
                    .call(() -> loadVersionId(tenantId, policyModule, policyFunction)))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(versionId -> evaluatePolicyWithVersionId(tenantId, policyModule, policyFunction, versionId, normalizedContext));
        }

        // 非动态加载模式，直接使用 null versionId
        return evaluatePolicyWithVersionId(tenantId, policyModule, policyFunction, null, normalizedContext);
    }

    /**
     * 内部方法：使用已解析的 versionId 评估策略
     */
    private Uni<PolicyEvaluationResult> evaluatePolicyWithVersionId(
            String tenantId,
            String policyModule,
            String policyFunction,
            String versionId,
            Object[] normalizedContext) {

        final PolicyCacheKey cacheKey = new PolicyCacheKey(tenantId, policyModule, policyFunction, versionId, normalizedContext);

        // 使用底层Caffeine缓存探测命中状态，避免fromCache标记失真
        Cache cache = policyCacheManager.getPolicyResultCache();
        final boolean cacheHit = policyCacheManager.isCacheHit(cacheKey);

        return cache
            .getAsync(cacheKey, this::evaluatePolicyWithKey)
            .invoke(result -> policyCacheManager.registerCacheEntry(cacheKey, tenantId))
            .onItem().transform(result -> adjustFromCacheFlag(result, cacheHit))
            .onFailure().invoke(throwable -> {
                if (!cacheHit) {
                    policyCacheManager.removeCacheEntry(cacheKey, tenantId);
                }
            });
    }

    /**
     * Internal method with cache key for proper caching
     */
    Uni<PolicyEvaluationResult> evaluatePolicyWithKey(PolicyCacheKey cacheKey) {

        return Uni.createFrom().item(() -> {
            try {
                // 检测是否处于工作流确定性上下文中
                DeterminismContext context = resolveDeterminismContext();
                boolean deterministicTiming = context != null;

                // 始终使用 System.nanoTime() 记录开始时间，确保时间计算准确
                long startMarker = System.nanoTime();

                // 使用动态执行路径，在独立事务中执行以确保并发批量评估的线程安全
                LOG.debugf("使用动态执行路径: %s.%s", cacheKey.getPolicyModule(), cacheKey.getPolicyFunction());
                PolicyEvaluationResult result = QuarkusTransaction.requiringNew()
                    .call(() -> evaluateDynamic(cacheKey, startMarker, deterministicTiming));

                return result;
            } catch (Throwable e) {
                throw new RuntimeException("Policy evaluation failed", e);
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 加载策略版本ID（用于缓存键）
     */
    private String loadVersionId(String tenantId, String policyModule, String policyFunction) {
        try {
            java.util.Optional<PolicyCatalog> catalog = policySourceRepository.findActiveCatalog(
                tenantId, policyModule, policyFunction
            );
            if (catalog.isEmpty()) {
                return null;
            }

            java.util.Optional<PolicyVersion> version = policySourceRepository.findActiveVersion(catalog.get().id);
            return version.map(v -> String.valueOf(v.id)).orElse(null);
        } catch (Exception e) {
            LOG.warnf(e, "加载版本ID失败: %s.%s", policyModule, policyFunction);
            return null;
        }
    }

    /**
     * 动态执行路径：从数据库加载策略并执行
     */
    private PolicyEvaluationResult evaluateDynamic(PolicyCacheKey cacheKey, long startMarker, boolean deterministicTiming) {
        try {
            // 1. 从数据库加载策略
            java.util.Optional<PolicyCatalog> catalog = policySourceRepository.findActiveCatalog(
                cacheKey.getTenantId(),
                cacheKey.getPolicyModule(),
                cacheKey.getPolicyFunction()
            );

            if (catalog.isEmpty()) {
                throw new RuntimeException(String.format(
                    "策略未找到: %s.%s (租户: %s)",
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction(),
                    cacheKey.getTenantId()
                ));
            }

            java.util.Optional<PolicyVersion> version = policySourceRepository.findActiveVersion(catalog.get().id);
            if (version.isEmpty()) {
                throw new RuntimeException(String.format(
                    "策略活跃版本未找到: %s.%s (租户: %s)",
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction(),
                    cacheKey.getTenantId()
                ));
            }

            String versionId = String.valueOf(version.get().id);

            // 2. 检查编译缓存
            java.util.Optional<io.aster.policy.cache.CompiledPolicy> cachedPolicy =
                compiledPolicyCache.get(
                    cacheKey.getTenantId(),
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction(),
                    versionId
                );

            String coreJson;
            CompilationMetadata metadata;

            if (cachedPolicy.isPresent()) {
                // 缓存命中，直接使用缓存的编译结果
                coreJson = cachedPolicy.get().getCoreJson();
                metadata = cachedPolicy.get().getMetadata();
                LOG.debugf("使用缓存的编译结果: %s.%s (version=%s)",
                    cacheKey.getPolicyModule(), cacheKey.getPolicyFunction(), versionId);
            } else {
                // 缓存未命中，编译策略并缓存
                CompilationResult compilationResult = policyCompiler.compile(version.get().id);
                if (!compilationResult.isSuccess()) {
                    throw new RuntimeException(String.format(
                        "策略编译失败: %s.%s - %s",
                        cacheKey.getPolicyModule(),
                        cacheKey.getPolicyFunction(),
                        compilationResult.getErrors()
                    ));
                }

                coreJson = compilationResult.getCoreJson();
                metadata = compilationResult.getMetadata();

                // 缓存编译结果
                io.aster.policy.cache.CompiledPolicy compiledPolicy = new io.aster.policy.cache.CompiledPolicy(
                    versionId,
                    version.get().sourceHash,
                    coreJson,
                    metadata
                );
                compiledPolicyCache.put(
                    cacheKey.getTenantId(),
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction(),
                    compiledPolicy
                );
            }

            // 3. 执行策略
            ExecutionResult executionResult = trufflePolicyRuntime.execute(
                coreJson,
                cacheKey.getContext(),
                metadata
            );

            if (!executionResult.success()) {
                throw new RuntimeException(String.format(
                    "策略执行失败: %s.%s - %s",
                    cacheKey.getPolicyModule(),
                    cacheKey.getPolicyFunction(),
                    executionResult.error()
                ));
            }

            // 4. 计算执行时间
            // 注意：始终使用 System.nanoTime() 计算实际执行时间，
            // 确定性计时仅用于工作流重放场景的输入参数，不应影响性能指标
            long endMarker = System.nanoTime();
            long durationNanos = deterministicTiming
                ? 0L  // 确定性模式下返回 0，表示时间不确定
                : Math.max(0, endMarker - startMarker);  // 确保非负

            return new PolicyEvaluationResult(
                executionResult.result(),
                durationNanos / 1_000_000.0,
                false
            );

        } catch (Exception e) {
            throw new RuntimeException(String.format(
                "动态策略执行异常: %s.%s",
                cacheKey.getPolicyModule(),
                cacheKey.getPolicyFunction()
            ), e);
        }
    }

    /**
     * 使缓存失效（针对特定策略，reactive版本）
     */
    public Uni<Void> invalidateCache(String tenantId, String policyModule, String policyFunction, Object[] context) {
        Object[] normalizedContext = normalizeContext(tenantId, context);
        PolicyCacheKey cacheKey = new PolicyCacheKey(tenantId, policyModule, policyFunction, normalizedContext);
        return invalidateCacheWithKey(cacheKey);
    }

    /**
     * 按租户维度批量失效缓存，可选过滤模块与函数
     */
    public Uni<Void> invalidateCache(String tenantId, String policyModule, String policyFunction) {
        Set<PolicyCacheKey> keys = policyCacheManager.snapshotTenantCacheKeys(tenantId);
        if (keys.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        boolean filterByModule = policyModule != null && !policyModule.isBlank();
        boolean filterByFunction = policyFunction != null && !policyFunction.isBlank();

        java.util.List<PolicyCacheKey> targets = keys.stream()
            .filter(key -> !filterByModule || Objects.equals(key.getPolicyModule(), policyModule))
            .filter(key -> !filterByFunction || Objects.equals(key.getPolicyFunction(), policyFunction))
            .collect(java.util.stream.Collectors.toList());

        if (targets.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        java.util.List<Uni<Void>> invalidations = new java.util.ArrayList<>();
        Cache cache = policyCacheManager.getPolicyResultCache();
        for (PolicyCacheKey key : targets) {
            invalidations.add(cache.invalidate(key)
                .invoke(() -> policyCacheManager.removeCacheEntry(key, key.getTenantId())));
        }

        return Uni.combine().all().unis(invalidations).discardItems();
    }

    /**
     * 仅用于测试：返回指定租户当前缓存键快照
     */
    public Set<PolicyCacheKey> snapshotTenantCacheKeys(String tenantId) {
        return policyCacheManager.snapshotTenantCacheKeys(tenantId);
    }

    /**
     * Internal method with cache key for cache invalidation
     */
    Uni<Void> invalidateCacheWithKey(PolicyCacheKey cacheKey) {
        return policyCacheManager.getPolicyResultCache().invalidate(cacheKey)
            .invoke(() -> policyCacheManager.removeCacheEntry(cacheKey, cacheKey.getTenantId()));
    }

    private Object[] normalizeContext(String tenantId, Object[] context) {
        if (context == null || context.length == 0) {
            return new Object[0];
        }

        Object[] copy = Arrays.copyOf(context, context.length);
        Object first = copy[0];
        String normalizedTenant = normalizeTenant(tenantId);
        if (first instanceof String tenantMarker &&
                normalizeTenant(tenantMarker).equals(normalizedTenant)) {
            return Arrays.copyOfRange(copy, 1, copy.length);
        }

        return copy;
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    private PolicyEvaluationResult adjustFromCacheFlag(PolicyEvaluationResult original, boolean fromCache) {
        if (original == null || original.isFromCache() == fromCache) {
            return original;
        }
        return new PolicyEvaluationResult(
            original.getResult(),
            original.getExecutionTimeMs(),
            fromCache
        );
    }

    /**
     * 根据当前 workflow 上下文决定是否启用确定性随机计时。
     *
     * 仅当显式处于工作流上下文中时才返回 DeterminismContext，
     * 避免普通策略执行时误用全局默认上下文导致计时错误。
     *
     * @return DeterminismContext 如果处于工作流上下文中，否则返回 null
     */
    private DeterminismContext resolveDeterminismContext() {
        try {
            // 使用 isInWorkflowContext() 检查是否有显式绑定的上下文
            // 避免误用全局默认上下文（非 replay 模式）
            if (workflowRuntime != null && workflowRuntime.isInWorkflowContext()) {
                return workflowRuntime.getDeterminismContext();
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 批量评估多个策略（并行执行，显著提升吞吐量，任一失败则全部失败）
     *
     * @param requests 策略评估请求列表
     * @return Uni包装的评估结果列表
     */
    @WithSpan("policy.evaluateBatch")
    public Uni<java.util.List<PolicyEvaluationResult>> evaluateBatch(
            java.util.List<BatchRequest> requests) {

        // 并行执行所有策略评估
        java.util.List<Uni<PolicyEvaluationResult>> unis = requests.stream()
            .map(req -> evaluatePolicy(req.tenantId, req.policyModule, req.policyFunction, req.context))
            .collect(java.util.stream.Collectors.toList());

        // 合并所有Uni结果使用现代API（任一失败则全部失败）
        return Uni.join().all(unis).andFailFast();
    }

    /**
     * 批量评估多个策略（并行执行，收集所有成功和失败结果）
     *
     * @param requests 策略评估请求列表
     * @return Uni包装的批量评估结果（包含成功和失败的结果）
     */
    public Uni<BatchEvaluationResult> evaluateBatchWithFailures(
            java.util.List<BatchRequest> requests) {

        // 为每个请求创建带错误处理的Uni
        java.util.List<Uni<EvaluationAttempt>> attempts = new java.util.ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            final int index = i;
            final BatchRequest req = requests.get(i);

            Uni<EvaluationAttempt> attempt = evaluatePolicy(req.tenantId, req.policyModule, req.policyFunction, req.context)
                .onItem().transform(result -> new EvaluationAttempt(
                    index,
                    req.policyModule,
                    req.policyFunction,
                    result,
                    null
                ))
                .onFailure().recoverWithItem(error -> new EvaluationAttempt(
                    index,
                    req.policyModule,
                    req.policyFunction,
                    null,
                    error.getMessage()
                ));

            attempts.add(attempt);
        }

        // 并行执行所有评估（不会因失败而中断）
        return Uni.join().all(attempts).andCollectFailures()
            .onItem().transform(attemptResults -> {
                // 分离成功和失败结果
                java.util.List<EvaluationAttempt> successes = new java.util.ArrayList<>();
                java.util.List<EvaluationAttempt> failures = new java.util.ArrayList<>();

                for (EvaluationAttempt attempt : attemptResults) {
                    if (attempt.getError() == null) {
                        successes.add(attempt);
                    } else {
                        failures.add(attempt);
                    }
                }

                return new BatchEvaluationResult(
                    successes,
                    failures,
                    successes.size(),
                    failures.size(),
                    requests.size()
                );
            });
    }

    /**
     * 策略组合执行（顺序执行多个策略，可选择前一个策略的结果作为下一个策略的输入）
     *
     * @param steps 策略组合步骤列表（按顺序执行）
     * @param initialContext 初始上下文参数
     * @return Uni包装的最终评估结果和中间结果列表
     */
    public Uni<PolicyCompositionResult> evaluateComposition(
            String tenantId,
            java.util.List<CompositionStep> steps,
            Object[] initialContext) {

        if (steps == null || steps.isEmpty()) {
            return Uni.createFrom().failure(
                new IllegalArgumentException("Composition steps cannot be empty")
            );
        }

        // 从初始上下文开始，顺序链接所有步骤
        return evaluateCompositionStep(tenantId, steps, 0, initialContext,
            new PolicyCompositionResult(new java.util.ArrayList<>(), null));
    }

    /**
     * 递归执行组合步骤
     */
    private Uni<PolicyCompositionResult> evaluateCompositionStep(
            String tenantId,
            java.util.List<CompositionStep> steps,
            int stepIndex,
            Object[] context,
            PolicyCompositionResult accumulator) {

        if (stepIndex >= steps.size()) {
            return Uni.createFrom().item(accumulator);
        }

        CompositionStep step = steps.get(stepIndex);

        // 执行当前步骤
        return evaluatePolicy(tenantId, step.policyModule, step.policyFunction, context)
            .chain(stepResult -> {
                // 记录步骤结果
                accumulator.getStepResults().add(new StepResult(
                    step.policyModule,
                    step.policyFunction,
                    stepResult.getResult(),
                    stepResult.getExecutionTimeMs(),
                    stepIndex
                ));

                // 更新最终结果
                accumulator.setFinalResult(stepResult.getResult());

                // 确定下一步的上下文
                Object[] nextContext;
                if (step.useResultAsInput && stepIndex < steps.size() - 1) {
                    // 使用当前步骤的结果作为下一步的输入
                    nextContext = new Object[]{stepResult.getResult()};
                } else {
                    // 继续使用原始上下文
                    nextContext = context;
                }

                // 递归执行下一步
                return evaluateCompositionStep(tenantId, steps, stepIndex + 1, nextContext, accumulator);
            });
    }

    /**
     * 清空所有缓存（reactive版本）
     */
    @CacheInvalidateAll(cacheName = "policy-results")
    public Uni<Void> clearAllCache() {
        // 清空策略缓存
        policyCacheManager.clearAllCache();
        // 返回completed Uni，缓存清空由注解处理
        return Uni.createFrom().voidItem();
    }

    /**
     * 验证策略是否存在并可以加载
     *
     * @param policyModule 策略模块名
     * @param policyFunction 策略函数名
     * @return Uni包装的验证结果
     */
    public Uni<PolicyValidationResult> validatePolicy(String policyModule, String policyFunction) {
        return Uni.createFrom().item(() -> {
            try {
                // 从数据库查找策略（使用当前租户上下文）
                String currentTenant = tenantContext.getCurrentTenant();
                java.util.Optional<PolicyCatalog> catalog = policySourceRepository.findActiveCatalog(
                    currentTenant,
                    policyModule,
                    policyFunction
                );

                if (catalog.isEmpty()) {
                    return new PolicyValidationResult(
                        false,
                        "Policy not found in database",
                        policyModule,
                        policyFunction,
                        null,
                        null,
                        null
                    );
                }

                // 获取活跃版本
                java.util.Optional<PolicyVersion> version = policySourceRepository.findActiveVersion(catalog.get().id);
                if (version.isEmpty()) {
                    return new PolicyValidationResult(
                        false,
                        "No active version found for policy",
                        policyModule,
                        policyFunction,
                        null,
                        null,
                        null
                    );
                }

                // 编译策略以获取元数据
                CompilationResult compilationResult = policyCompiler.compile(version.get().id);
                if (!compilationResult.isSuccess()) {
                    return new PolicyValidationResult(
                        false,
                        "Policy compilation failed: " + compilationResult.getErrors(),
                        policyModule,
                        policyFunction,
                        null,
                        null,
                        null
                    );
                }

                CompilationMetadata metadata = compilationResult.getMetadata();

                // 获取返回类型
                String returnType = metadata.getReturnType() != null ? metadata.getReturnType() : "unknown";
                String returnTypeFullName = returnType;

                // 从 Core JSON 解析参数信息
                java.util.List<ParameterInfo> parameters = extractParametersFromCoreJson(
                    compilationResult.getCoreJson(),
                    policyFunction
                );

                return new PolicyValidationResult(
                    true,
                    "Policy exists and is valid and callable",
                    policyModule,
                    policyFunction,
                    parameters,
                    returnType,
                    returnTypeFullName
                );
            } catch (Exception e) {
                return new PolicyValidationResult(
                    false,
                    "Policy validation failed: " + e.getMessage(),
                    policyModule,
                    policyFunction,
                    null,
                    null,
                    null
                );
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 从 Core JSON 解析函数参数信息
     *
     * Core JSON 结构示例:
     * {
     *   "decls": [
     *     {
     *       "@type": "Func",
     *       "name": "evaluateLoanEligibility",
     *       "params": [
     *         { "name": "申请", "type": "..." },
     *         { "name": "年龄", "type": "..." }
     *       ]
     *     }
     *   ]
     * }
     */
    private java.util.List<ParameterInfo> extractParametersFromCoreJson(String coreJson, String functionName) {
        java.util.List<ParameterInfo> parameters = new java.util.ArrayList<>();
        if (coreJson == null || coreJson.isBlank()) {
            return parameters;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(coreJson);
            com.fasterxml.jackson.databind.JsonNode decls = root.get("decls");

            if (decls == null || !decls.isArray()) {
                return parameters;
            }

            for (com.fasterxml.jackson.databind.JsonNode decl : decls) {
                // 支持两种格式：@type (旧版) 和 kind (新版 Core IR)
                String type = decl.has("@type") ? decl.get("@type").asText()
                    : (decl.has("kind") ? decl.get("kind").asText() : null);
                String name = decl.has("name") ? decl.get("name").asText() : null;

                // 查找 Func 类型声明
                if ("Func".equals(type) && functionName.equals(name)) {
                    com.fasterxml.jackson.databind.JsonNode params = decl.get("params");
                    if (params != null && params.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode param : params) {
                            String paramName = param.has("name") ? param.get("name").asText() : "unknown";
                            String paramType = param.has("type") ? param.get("type").toString() : "Object";
                            parameters.add(new ParameterInfo(paramName, paramType, paramType));
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "解析 Core JSON 参数信息失败: %s", functionName);
        }

        return parameters;
    }

}
