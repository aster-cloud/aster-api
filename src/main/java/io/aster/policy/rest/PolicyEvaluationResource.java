package io.aster.policy.rest;

import io.aster.billing.ApiQuotaGuard;
import io.aster.policy.common.PolicySerializer;
import io.aster.monitoring.BusinessMetrics;
import io.aster.policy.api.PolicyEvaluationService;
import io.aster.policy.api.model.BatchRequest;
import io.aster.policy.event.AuditEvent;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.metrics.PolicyMetrics;
import io.aster.policy.api.convert.NamedContextMapper;
import io.aster.policy.api.schema.ParameterSchemaExtractor;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.rest.model.*;
import io.aster.policy.service.PolicyVersionService;
import io.aster.policy.telemetry.NsmEvents;
import io.aster.policy.telemetry.NsmTelemetry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * REST API资源：策略评估服务
 *
 * 提供策略评估、批量评估、验证和缓存管理的RESTful接口。
 * 支持通过 X-Tenant-Id 头部实现多租户隔离。
 */
@Path("/api/v1/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireRole(Role.MEMBER)
public class PolicyEvaluationResource {

    private static final Logger LOG = Logger.getLogger(PolicyEvaluationResource.class);

    @Inject
    PolicyEvaluationService evaluationService;

    @Inject
    PolicyMetrics policyMetrics;

    @Inject
    BusinessMetrics businessMetrics;

    @Inject
    PolicyVersionService versionService;

    @Inject
    Event<AuditEvent> auditEventPublisher;

    @Inject
    NsmTelemetry nsmTelemetry;

    @Inject
    ApiQuotaGuard apiQuotaGuard;

    @Context
    RoutingContext routingContext;

    /**
     * 评估单个策略
     *
     * POST /api/policies/evaluate
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "policyModule": "aster.finance.loan", "policyFunction": "evaluateLoanEligibility", "context": [{...}, {...}] }
     */
    @POST
    @Path("/evaluate")
    public Uni<EvaluationResponse> evaluate(@Valid EvaluationRequest request) {
        enforceApiQuota("/api/v1/policies/evaluate");
        String tenantId = tenantId();
        String performedBy = performedBy();
        long startTime = System.currentTimeMillis();
        Timer.Sample sample = businessMetrics.startPolicyEvaluation();
        Map<String, Object> metadata = buildEvaluationMetadata(request);

        LOG.infof("Evaluating policy: %s.%s for tenant %s", request.policyModule(), request.policyFunction(), tenantId);

        return evaluationService.evaluatePolicy(
                tenantId,
                request.policyModule(),
                request.policyFunction(),
                request.context()
        )
        .onItem().transform(result -> {
            long executionTime = System.currentTimeMillis() - startTime;

            // 记录指标（非阻塞）
            policyMetrics.recordEvaluation(request.policyModule(), request.policyFunction(), executionTime, true);
            businessMetrics.recordPolicyEvaluation();
            businessMetrics.endPolicyEvaluation(sample);

            // 记录业务指标（贷款批准/拒绝）
            if ("aster.finance.loan".equals(request.policyModule())) {
                recordLoanDecision(result.getResult());
            }

            publishPolicyEvaluationEvent(
                tenantId,
                request,
                performedBy,
                true,
                executionTime,
                null,
                metadata
            );

            LOG.infof("Policy evaluation completed in %dms: %s.%s", executionTime, request.policyModule(), request.policyFunction());
            recordApiCall("/api/v1/policies/evaluate", "success", executionTime);
            return EvaluationResponse.success(result.getResult(), executionTime);
        })
        .onFailure().recoverWithItem(throwable -> {
            long executionTime = System.currentTimeMillis() - startTime;

            // 记录错误指标（非阻塞）
            policyMetrics.recordEvaluation(request.policyModule(), request.policyFunction(), executionTime, false);
            businessMetrics.endPolicyEvaluation(sample);

            publishPolicyEvaluationEvent(
                tenantId,
                request,
                performedBy,
                false,
                executionTime,
                throwable.getMessage(),
                metadata
            );

            LOG.errorf(throwable, "Policy evaluation failed after %dms: %s.%s", executionTime, request.policyModule(), request.policyFunction());
            recordApiCall("/api/v1/policies/evaluate", "api_error", executionTime);
            return EvaluationResponse.error(throwable.getMessage());
        });
    }

    /**
     * 评估 JSON 格式策略
     *
     * POST /api/policies/evaluate-json
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "policy": "{...Core IR JSON...}", "context": {...} }
     */
    @POST
    @Path("/evaluate-json")
    public Uni<EvaluationResponse> evaluateJson(@Valid JsonPolicyRequest request) {
        enforceApiQuota("/api/v1/policies/evaluate-json");
        String tenantId = tenantId();
        String performedBy = performedBy();
        long startTime = System.currentTimeMillis();
        Timer.Sample sample = businessMetrics.startPolicyEvaluation();

        LOG.infof("Evaluating JSON policy for tenant %s", tenantId);

        try {
            // 1. 将 JSON 转换为 CNL
            PolicySerializer serializer = new PolicySerializer();
            String cnl = serializer.toCNL(request.policy());

            // 2. 从 CNL 中提取 module 和 function 名称
            String policyModule = extractModule(cnl);
            String policyFunction = extractFunction(cnl);

            LOG.infof("Extracted policy: %s.%s", policyModule, policyFunction);

            // 3. 准备上下文数组
            Object[] contextArray;
            if (request.context() instanceof List<?> list) {
                // JSON 数组被反序列化为 List
                contextArray = list.toArray();
            } else if (request.context() instanceof Object[] arr) {
                // 直接传入数组（不太可能从 REST 请求中出现）
                contextArray = arr;
            } else if (request.context() instanceof Map) {
                // 单个对象包装为数组
                contextArray = new Object[] { request.context() };
            } else {
                // 其他类型也包装为数组
                contextArray = new Object[] { request.context() };
            }

            // 4. 执行评估（复用现有逻辑）
            return evaluationService.evaluatePolicy(
                    tenantId,
                    policyModule,
                    policyFunction,
                    contextArray
            )
            .onItem().transform(result -> {
                long executionTime = System.currentTimeMillis() - startTime;

                // 记录指标（非阻塞）
                policyMetrics.recordEvaluation(policyModule, policyFunction, executionTime, true);
                businessMetrics.recordPolicyEvaluation();
                businessMetrics.endPolicyEvaluation(sample);

                // 记录业务指标（贷款批准/拒绝）
                if ("aster.finance.loan".equals(policyModule)) {
                    recordLoanDecision(result.getResult());
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sourceFormat", "json");

                publishPolicyEvaluationEvent(
                    tenantId,
                    new EvaluationRequest(policyModule, policyFunction, contextArray),
                    performedBy,
                    true,
                    executionTime,
                    null,
                    metadata
                );

                LOG.infof("JSON policy evaluation completed in %dms: %s.%s", executionTime, policyModule, policyFunction);
                recordApiCall("/api/v1/policies/evaluate-json", "success", executionTime);
                return EvaluationResponse.success(result.getResult(), executionTime);
            })
            .onFailure().recoverWithItem(throwable -> {
                long executionTime = System.currentTimeMillis() - startTime;

                // 记录错误指标（非阻塞）
                policyMetrics.recordEvaluation(policyModule, policyFunction, executionTime, false);
                businessMetrics.endPolicyEvaluation(sample);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sourceFormat", "json");

                publishPolicyEvaluationEvent(
                    tenantId,
                    new EvaluationRequest(policyModule, policyFunction, contextArray),
                    performedBy,
                    false,
                    executionTime,
                    throwable.getMessage(),
                    metadata
                );

                LOG.errorf(throwable, "JSON policy evaluation failed after %dms: %s.%s", executionTime, policyModule, policyFunction);
                recordApiCall("/api/v1/policies/evaluate-json", "api_error", executionTime);
                return EvaluationResponse.error(throwable.getMessage());
            });
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            businessMetrics.endPolicyEvaluation(sample);

            LOG.errorf(e, "Failed to process JSON policy: %s", e.getMessage());
            recordApiCall("/api/v1/policies/evaluate-json", "api_error", executionTime);
            return Uni.createFrom().item(EvaluationResponse.error("JSON 策略解析失败: " + e.getMessage()));
        }
    }

    /**
     * 直接评估 CNL 源代码
     *
     * POST /api/policies/evaluate-source
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "source": "module aster.example ... ", "context": {...}, "locale": "en-US", "functionName": "evaluate" }
     *
     * 适用于 Dashboard 执行场景，无需预先部署策略。
     * 使用动态执行流程：CNL → AST → Core IR → JSON → GraalVM Polyglot
     *
     * 支持两种 context 格式：
     * 1. 命名格式: { "申请": {...}, "年龄": 25 } - 参数名与函数定义匹配
     * 2. 位置格式: [{...}, 25] - 按位置顺序传参
     */
    @POST
    @Path("/evaluate-source")
    public Uni<EvaluationResponse> evaluateSource(
        @Valid SourcePolicyRequest request,
        @QueryParam("trace") @DefaultValue("false") boolean trace
    ) {
        enforceApiQuota("/api/v1/policies/evaluate-source");
        String tenantId = tenantId();
        String performedBy = performedBy();
        // 必须在切到 worker pool 前抓取 apiKeyId — RequestScoped 在 lambda
        // 内已失效，否则 recordApiCall 会抛 ContextNotActiveException 并
        // 被 Mutiny drop（quota 计数 silently lost）。
        String apiKeyIdSnap = (routingContext != null && routingContext.request() != null)
            ? routingContext.request().getHeader("X-Api-Key-Id")
            : null;
        long apiCallStart = System.currentTimeMillis();
        Timer.Sample sample = businessMetrics.startPolicyEvaluation();

        LOG.infof("Evaluating CNL source for tenant %s (locale=%s, function=%s)",
            tenantId, request.getLocaleOrDefault(), request.getFunctionNameOrDefault());

        // 使用 Uni.createFrom().item() 包装同步执行，避免阻塞主线程
        return Uni.createFrom().item(() -> {
            try {
                // 使用动态执行器执行 CNL，支持命名上下文格式
                // executeWithContext 会自动检测并映射命名参数到位置参数
                DynamicCnlExecutor.ExecutionResult execResult = DynamicCnlExecutor.executeWithContext(
                    request.source(),
                    request.context(),  // 直接传递原始 context，让执行器处理格式映射
                    request.getFunctionNameOrDefault(),
                    request.getLocaleOrDefault()
                );

                // 记录指标
                policyMetrics.recordEvaluation(
                    execResult.moduleName(),
                    execResult.functionName(),
                    execResult.executionTimeMs(),
                    true
                );
                businessMetrics.recordPolicyEvaluation();
                businessMetrics.endPolicyEvaluation(sample);

                // 记录业务指标（贷款批准/拒绝）
                if ("aster.finance.loan".equals(execResult.moduleName())) {
                    recordLoanDecision(execResult.result());
                }

                // 发布审计事件
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sourceFormat", "cnl");
                metadata.put("locale", request.getLocaleOrDefault());
                metadata.put("dynamicExecution", true);
                metadata.put("namedContext", request.context() instanceof Map);

                publishPolicyEvaluationEvent(
                    tenantId,
                    new EvaluationRequest(execResult.moduleName(), execResult.functionName(), new Object[]{request.context()}),
                    performedBy,
                    true,
                    execResult.executionTimeMs(),
                    null,
                    metadata
                );

                LOG.infof("CNL source evaluation completed in %dms: %s.%s",
                    execResult.executionTimeMs(), execResult.moduleName(), execResult.functionName());

                if (trace) {
                    var decisionTrace = new io.aster.policy.api.model.DecisionTrace(
                        execResult.moduleName(),
                        execResult.functionName(),
                        List.of(),
                        execResult.result(),
                        execResult.executionTimeMs()
                    );
                    recordApiCall("/api/v1/policies/evaluate-source", "success",
                        System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                    return EvaluationResponse.success(execResult.result(), execResult.executionTimeMs(), decisionTrace);
                }
                recordApiCall("/api/v1/policies/evaluate-source", "success",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                return EvaluationResponse.success(execResult.result(), execResult.executionTimeMs());

            } catch (DynamicCnlExecutor.DynamicExecutionException e) {
                businessMetrics.endPolicyEvaluation(sample);
                LOG.errorf(e, "Dynamic CNL execution failed: %s", e.getMessage());
                recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                return EvaluationResponse.error("CNL 动态执行失败: " + e.getMessage());

            } catch (InProcessCnlParser.CnlParseException e) {
                businessMetrics.endPolicyEvaluation(sample);
                LOG.errorf(e, "CNL parsing failed: %s", e.getMessage());
                recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                return EvaluationResponse.error("CNL 解析失败: " + e.getMessage());

            } catch (Exception e) {
                businessMetrics.endPolicyEvaluation(sample);
                LOG.errorf(e, "Failed to process CNL source: %s", e.getMessage());
                recordApiCall("/api/v1/policies/evaluate-source", "api_error",
                    System.currentTimeMillis() - apiCallStart, tenantId, performedBy, apiKeyIdSnap);
                return EvaluationResponse.error("CNL 策略执行失败: " + e.getMessage());
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 获取 CNL 源代码的参数模式
     *
     * POST /api/policies/schema
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "source": "module aster.example ... ", "locale": "en-US", "functionName": "evaluate" }
     *
     * 返回函数参数的结构化模式信息，用于：
     * 1. 动态生成表单（根据参数名和类型生成输入控件）
     * 2. API 客户端参数提示
     * 3. 文档生成
     */
    @POST
    @Path("/schema")
    public Uni<SchemaResponse> getSchema(@Valid SchemaRequest request) {
        LOG.infof("Extracting schema from CNL source (locale=%s, function=%s)",
            request.getLocaleOrDefault(), request.getFunctionNameOrDefault());

        return Uni.createFrom().item(() -> {
            try {
                ParameterSchemaExtractor.SchemaResult result = ParameterSchemaExtractor.extractSchema(
                    request.source(),
                    request.getFunctionNameOrDefault(),
                    request.getLocaleOrDefault()
                );

                if (result.success()) {
                    LOG.infof("Schema extracted successfully: %s.%s with %d parameters",
                        result.moduleName(), result.functionName(), result.parameters().size());
                    return SchemaResponse.success(result);
                } else {
                    LOG.warnf("Schema extraction failed: %s", result.error());
                    return SchemaResponse.error(result.error());
                }

            } catch (Exception e) {
                LOG.errorf(e, "Failed to extract schema: %s", e.getMessage());
                return SchemaResponse.error("模式提取失败: " + e.getMessage());
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 准备上下文数组（统一处理 List、数组、Map 等格式）
     */
    private Object[] prepareContextArray(Object context) {
        if (context instanceof List<?> list) {
            return list.toArray();
        } else if (context instanceof Object[] arr) {
            return arr;
        } else if (context instanceof Map) {
            return new Object[] { context };
        } else {
            return new Object[] { context };
        }
    }

    /**
     * 批量评估多个策略
     *
     * POST /api/policies/evaluate/batch
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "requests": [{ "policyModule": "...", "policyFunction": "...", "context": [...] }, ...] }
     */
    @POST
    @Path("/evaluate/batch")
    public Uni<BatchEvaluationResponse> evaluateBatch(@Valid BatchEvaluationRequest request) {
        // batch 按"批中每条都算一次调用"扣配额：在 quota 检查之外，结果落地时按条 recordApiCall
        enforceApiQuota("/api/v1/policies/evaluate/batch");
        String tenantId = tenantId();
        long startTime = System.currentTimeMillis();

        LOG.infof("Batch evaluating %d policies for tenant %s", request.requests().size(), tenantId);

        // 转换为内部批量请求格式
        List<BatchRequest> batchRequests = request.requests().stream()
            .map(req -> new BatchRequest(
                tenantId,
                req.policyModule(),
                req.policyFunction(),
                req.context()
            ))
            .toList();

        return evaluationService.evaluateBatchWithFailures(batchRequests)
            .map(batchResult -> {
                long totalExecutionTime = System.currentTimeMillis() - startTime;

                // 转换为REST响应格式
                List<EvaluationResponse> responses = new ArrayList<>();

                // 添加成功的结果
                for (var attempt : batchResult.getSuccesses()) {
                    long execTime = (long) attempt.getResult().getExecutionTimeMs();

                    // 记录成功指标
                    policyMetrics.recordEvaluation(
                        attempt.getPolicyModule(),
                        attempt.getPolicyFunction(),
                        execTime,
                        true
                    );

                    // 记录业务指标
                    if ("aster.finance.loan".equals(attempt.getPolicyModule())) {
                        recordLoanDecision(attempt.getResult().getResult());
                    }
                    businessMetrics.recordPolicyEvaluation();

                    responses.add(EvaluationResponse.success(
                        attempt.getResult().getResult(),
                        execTime
                    ));
                    recordApiCall("/api/v1/policies/evaluate/batch", "success", execTime);
                }

                // 添加失败的结果
                for (var attempt : batchResult.getFailures()) {
                    long execTime = totalExecutionTime / batchRequests.size(); // 估算

                    // 记录失败指标
                    policyMetrics.recordEvaluation(
                        attempt.getPolicyModule(),
                        attempt.getPolicyFunction(),
                        execTime,
                        false
                    );

                    responses.add(EvaluationResponse.error(attempt.getError()));
                    recordApiCall("/api/v1/policies/evaluate/batch", "api_error", execTime);
                }

                LOG.infof("Batch evaluation completed in %dms: %d success, %d failures",
                    totalExecutionTime, batchResult.getSuccessCount(), batchResult.getFailureCount());

                return new BatchEvaluationResponse(
                    responses,
                    totalExecutionTime,
                    batchResult.getSuccessCount(),
                    batchResult.getFailureCount()
                );
            });
    }

    /**
     * 验证策略是否存在且可调用
     *
     * POST /api/policies/validate
     * Body: { "policyModule": "aster.finance.loan", "policyFunction": "evaluateLoanEligibility" }
     */
    @POST
    @Path("/validate")
    public Uni<ValidationResponse> validate(@Valid ValidationRequest request) {
        LOG.infof("Validating policy: %s.%s", request.policyModule(), request.policyFunction());

        return evaluationService.validatePolicy(
                request.policyModule(),
                request.policyFunction()
        )
        .map(result -> {
            if (result.isValid()) {
                LOG.infof("Policy validation successful: %s.%s", request.policyModule(), request.policyFunction());
                return ValidationResponse.success(
                    result.getParameters() != null ? result.getParameters().size() : 0,
                    result.getReturnType()
                );
            } else {
                LOG.warnf("Policy validation failed: %s.%s - %s", request.policyModule(), request.policyFunction(), result.getMessage());
                return ValidationResponse.failure(result.getMessage());
            }
        });
    }

    /**
     * 清除策略缓存
     *
     * DELETE /api/policies/cache
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "policyModule": "aster.finance.loan", "policyFunction": "evaluateLoanEligibility" } (both fields optional)
     */
    @DELETE
    @Path("/cache")
    public Uni<CacheClearResponse> clearCache(@Valid CacheClearRequest request) {
        String tenantId = tenantId();

        LOG.infof("Clearing cache for tenant %s: module=%s, function=%s",
            tenantId, request.policyModule(), request.policyFunction());

        return evaluationService.invalidateCache(
                tenantId,
                request.policyModule(),
                request.policyFunction()
        )
        .map(v -> {
            String message = String.format("Cache cleared for tenant %s", tenantId);
            if (request.policyModule() != null) {
                message += ", module=" + request.policyModule();
            }
            if (request.policyFunction() != null) {
                message += ", function=" + request.policyFunction();
            }
            LOG.infof(message);
            return CacheClearResponse.success(message);
        })
        .onFailure().recoverWithItem(throwable -> {
            LOG.errorf(throwable, "Failed to clear cache for tenant %s", tenantId);
            return CacheClearResponse.failure(throwable.getMessage());
        });
    }

    /**
     * 回滚策略到指定版本
     *
     * POST /api/policies/{policyId}/rollback
     * Headers: X-Tenant-Id (optional, defaults to "default")
     * Body: { "targetVersion": 1730890123456, "reason": "回滚原因" }
     */
    @POST
    @Path("/{policyId}/rollback")
    @io.smallrye.common.annotation.Blocking
    public Uni<RollbackResponse> rollback(
        @PathParam("policyId") String policyId,
        @Valid RollbackRequest request
    ) {
        String tenantId = tenantId();
        String performedBy = performedBy();

        LOG.infof("Rolling back policy %s to version %d for tenant %s",
            policyId, request.targetVersion(), tenantId);

        return Uni.createFrom().item(() -> {
            // 获取当前活跃版本（用于审计日志）
            PolicyVersion currentVersion = versionService.getActiveVersion(policyId);
            Long fromVersion = currentVersion != null ? currentVersion.version : null;

            // 执行回滚
            PolicyVersion rolledBackVersion = versionService.rollbackToVersion(
                policyId,
                request.targetVersion()
            );

            auditEventPublisher.fireAsync(
                AuditEvent.rollback(
                    tenantId,
                    rolledBackVersion.moduleName,
                    policyId,
                    fromVersion,
                    rolledBackVersion.version,
                    performedBy,
                    request.reason()
                )
            );

            // NSM 埋点：rule_rolled_back（详见 03-telemetry-spec.md）
            // days_after_publish 暂留 -1，待 PolicyVersion 加入 publishedAt/activatedAt 后回填精确值
            long daysAfterPublish = -1L;
            if (currentVersion != null && currentVersion.activatedAt != null) {
                daysAfterPublish = java.time.Duration.between(
                    currentVersion.activatedAt, java.time.Instant.now()
                ).toDays();
            }
            nsmTelemetry.track(
                performedBy,
                NsmEvents.RULE_ROLLED_BACK,
                java.util.Map.of(
                    "rule_id", policyId,
                    "from_version", fromVersion != null ? fromVersion : -1,
                    "to_version", rolledBackVersion.version,
                    "days_after_publish", daysAfterPublish,
                    "reason", request.reason() != null ? request.reason() : "",
                    "tenant_id", tenantId
                )
            );

            LOG.infof("Policy %s successfully rolled back to version %d",
                policyId, rolledBackVersion.version);

            return RollbackResponse.success(rolledBackVersion.version);
        })
        .onFailure(IllegalArgumentException.class)
        .recoverWithItem(throwable -> {
            LOG.errorf(throwable, "Rollback failed for policy %s", policyId);
            return RollbackResponse.failure("版本不存在: " + request.targetVersion());
        })
        .onFailure().recoverWithItem(throwable -> {
            LOG.errorf(throwable, "Rollback failed for policy %s", policyId);
            return RollbackResponse.failure("回滚失败: " + throwable.getMessage());
        });
    }

    /**
     * 获取策略版本历史
     *
     * GET /api/policies/{policyId}/versions
     * Headers: X-Tenant-Id (optional, defaults to "default")
     */
    @GET
    @Path("/{policyId}/versions")
    @io.smallrye.common.annotation.Blocking
    public Uni<List<PolicyVersionInfo>> getVersionHistory(@PathParam("policyId") String policyId) {
        String tenantId = tenantId();

        LOG.infof("Fetching version history for policy %s (tenant: %s)", policyId, tenantId);

        return Uni.createFrom().item(() -> {
            List<PolicyVersion> versions = versionService.getAllVersions(policyId);
            return versions.stream()
                .map(v -> new PolicyVersionInfo(
                    v.version,
                    v.active,
                    v.moduleName,
                    v.functionName,
                    v.createdAt,
                    v.createdBy,
                    v.notes
                ))
                .toList();
        });
    }

    private void publishPolicyEvaluationEvent(
        String tenantId,
        EvaluationRequest request,
        String performedBy,
        boolean success,
        long executionTimeMs,
        String errorMessage,
        Map<String, Object> metadata
    ) {
        if (auditEventPublisher == null) {
            return;
        }
        auditEventPublisher.fireAsync(
            AuditEvent.policyEvaluation(
                tenantId,
                request.policyModule(),
                request.policyFunction(),
                performedBy,
                success,
                executionTimeMs,
                errorMessage,
                metadata == null ? Collections.emptyMap() : metadata
            )
        );
    }

    private Map<String, Object> buildEvaluationMetadata(EvaluationRequest request) {
        if (request == null || request.context() == null || request.context().length == 0) {
            return Collections.emptyMap();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("contextSize", request.context().length);

        Object firstContext = request.context()[0];
        if (firstContext instanceof Map<?, ?> contextMap) {
            Object applicantId = contextMap.get("applicantId");
            if (applicantId != null) {
                metadata.put("applicantId", applicantId.toString());
            }
        }

        return metadata.isEmpty() ? Collections.emptyMap() : metadata;
    }

    /**
     * 提取租户ID
     *
     * 从 X-Tenant-Id 请求头提取租户ID，如果不存在则返回 "default"
     */
    private String tenantId() {
        if (routingContext == null || routingContext.request() == null) {
            return "default";
        }
        String tenant = routingContext.request().getHeader("X-Tenant-Id");
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim();
    }

    /**
     * 提取执行者信息
     *
     * 从 X-User-Id 请求头提取用户ID，如果不存在则返回 "anonymous"
     */
    private String performedBy() {
        if (routingContext == null || routingContext.request() == null) {
            return "anonymous";
        }
        String user = routingContext.request().getHeader("X-User-Id");
        return user == null || user.isBlank() ? "anonymous" : user.trim();
    }

    /**
     * 同步检查 API 配额；命中即抛 WebApplicationException
     * 写入 X-Quota-Limit / Remaining / Reset / Warning 响应头（API-5）
     *
     * 已用 == 0 而 limit == 0 时仍会写 0/0 的响应头，给客户端清晰信号"plan 不开放"
     */
    private ApiQuotaGuard.GuardResult enforceApiQuota(String endpointPath) {
        String tenantId = tenantId();
        String userId = performedBy();
        ApiQuotaGuard.GuardResult result = apiQuotaGuard.check(tenantId, userId);

        // 写响应头（API-5）
        if (routingContext != null && routingContext.response() != null) {
            String limitStr = result.limit() == -1 ? "unlimited" : String.valueOf(result.limit());
            routingContext.response().putHeader("X-Quota-Limit", limitStr);
            if (result.limit() != -1) {
                long remaining = Math.max(0, result.limit() - result.used());
                routingContext.response().putHeader("X-Quota-Remaining", String.valueOf(remaining));
                routingContext.response().putHeader("X-Quota-Reset", String.valueOf(monthStartUnix()));
            }
            if (result.warning() != null) {
                routingContext.response().putHeader("X-Quota-Warning", result.warning());
            }
        }

        switch (result.verdict()) {
            case FORBIDDEN -> throw new WebApplicationException(
                jakarta.ws.rs.core.Response.status(403)
                    .entity(Map.of(
                        "error", "api_access_denied",
                        "message", "Free 计划不包含 Policy Execution API。请升级到 Pro/Team 或试用 Trial。"
                    ))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
            case RATE_LIMITED -> throw new WebApplicationException(
                jakarta.ws.rs.core.Response.status(429)
                    .entity(Map.of(
                        "error", "api_quota_hard_exceeded",
                        "message", "本月 API 调用已超 200% 上限，已拒绝。请升级套餐或联系客服。",
                        "limit", result.limit(),
                        "used", result.used()
                    ))
                    .type(MediaType.APPLICATION_JSON)
                    .build()
            );
            default -> { /* ALLOW: 即使 soft warn 也继续 */ }
        }

        // API-7: per-API-key per-second 限流（仅当请求带 X-Api-Key-Id 时生效）
        String apiKeyId = null;
        if (routingContext != null && routingContext.request() != null) {
            apiKeyId = routingContext.request().getHeader("X-Api-Key-Id");
        }
        if (apiKeyId != null && !apiKeyId.isBlank()) {
            ApiQuotaGuard.RateCheck rate = apiQuotaGuard.checkRate(tenantId, apiKeyId);
            if (routingContext != null && routingContext.response() != null) {
                routingContext.response().putHeader("X-RateLimit-Limit", String.valueOf(rate.limit()));
                routingContext.response().putHeader("X-RateLimit-Remaining",
                    String.valueOf(Math.max(0, rate.limit() - rate.used())));
            }
            if (!rate.allowed()) {
                throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(429)
                        .header("Retry-After", "1")
                        .entity(Map.of(
                            "error", "rate_limit_exceeded",
                            "message", "Per-API-key per-second 限流：超过 " + rate.limit() + " RPS",
                            "rps_limit", rate.limit(),
                            "rps_used", rate.used()
                        ))
                        .type(MediaType.APPLICATION_JSON)
                        .build()
                );
            }
        }
        return result;
    }

    /**
     * 异步记录一次 API 调用（fire-and-forget）。
     *
     * 仅可在 RequestScoped 仍然有效时调用（同步路径 / 主 event-loop 线程）。
     * 若调用点已切换到 worker pool / Uni lambda，RequestScoped 已失效，
     * 必须改用下面的 {@link #recordApiCall(String, String, long, String, String, String)}
     * 重载并把 tenant/user/apiKey 预先捕获后传入。
     */
    private void recordApiCall(String endpointPath, String status, long latencyMs) {
        String tenantId = tenantId();
        String userId = performedBy();
        String apiKeyId = null;
        if (routingContext != null && routingContext.request() != null) {
            apiKeyId = routingContext.request().getHeader("X-Api-Key-Id");
        }
        apiQuotaGuard.recordAsync(userId, tenantId, apiKeyId, endpointPath, status, latencyMs);
    }

    /**
     * 异步路径专用：tenant/user/apiKey 必须在跨线程跳板前预捕获，
     * 否则 routingContext 会在 worker thread 上触发
     * ContextNotActiveException，被 Mutiny drop 后 quota 计数静默丢失。
     */
    private void recordApiCall(String endpointPath, String status, long latencyMs,
                               String tenantId, String userId, String apiKeyId) {
        apiQuotaGuard.recordAsync(userId, tenantId, apiKeyId, endpointPath, status, latencyMs);
    }

    private static long monthStartUnix() {
        java.time.YearMonth ym = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        return ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
    }

    /**
     * 记录贷款决策指标
     *
     * 根据策略结果判断是批准还是拒绝，并记录相应指标
     *
     * @param result 策略评估结果
     */
    private void recordLoanDecision(Object result) {
        if (result == null) {
            return;
        }

        // 检查结果是否表示批准
        // 支持多种结果格式：
        // 1. Boolean 值
        // 2. 包含 "approved" 字段的对象
        // 3. 字符串 "APPROVED" / "REJECTED"
        boolean approved = false;

        if (result instanceof Boolean boolResult) {
            approved = boolResult;
        } else if (result instanceof String strResult) {
            approved = "APPROVED".equalsIgnoreCase(strResult) || "true".equalsIgnoreCase(strResult);
        } else {
            // 尝试通过反射检查 approved 字段
            try {
                var method = result.getClass().getMethod("isApproved");
                if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
                    approved = (Boolean) method.invoke(result);
                }
            } catch (Exception e) {
                // 无法确定批准状态，不记录
                return;
            }
        }

        if (approved) {
            policyMetrics.recordLoanApproval();
        } else {
            policyMetrics.recordLoanRejection();
        }
    }

    /**
     * 从 CNL 中提取模块名称
     *
     * 匹配 "Module <module_name>." 语法
     *
     * @param cnl CNL 格式的策略代码
     * @return 模块名称
     * @throws IllegalArgumentException 如果无法提取模块名称
     */
    private String extractModule(String cnl) {
        // 匹配 "Module <module_name>." 或 "// module <module_name>"
        Pattern modulePattern = Pattern.compile("(?:Module|// module)\\s+([\\w.]+?)(?:\\.\\s|\\s|$)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = modulePattern.matcher(cnl);

        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new IllegalArgumentException("无法从 CNL 中提取模块名称");
    }

    /**
     * 从 CNL 中提取函数名称
     *
     * 匹配 "Rule <function_name> given..." 或 "func <function_name>(...)" 语法
     *
     * @param cnl CNL 格式的策略代码
     * @return 函数名称
     * @throws IllegalArgumentException 如果无法提取函数名称
     */
    private String extractFunction(String cnl) {
        // 匹配 "Rule <function_name> given" 或 "Rule <function_name>:" 或 "func <function_name>("
        Pattern functionPattern = Pattern.compile("(?:Rule|func)\\s+(\\w+)\\s*(?:given|\\(|:)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = functionPattern.matcher(cnl);

        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new IllegalArgumentException("无法从 CNL 中提取函数名称");
    }
}
