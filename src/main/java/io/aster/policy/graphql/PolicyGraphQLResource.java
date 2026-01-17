package io.aster.policy.graphql;

import io.aster.audit.inbox.InboxGuard;
import io.aster.policy.api.CacheManagementService;
import io.aster.policy.api.CacheManagementService.CacheOperationResult;
import io.aster.policy.api.PolicyManagementService;
import io.aster.policy.api.PolicyQueryService;
import io.aster.policy.graphql.types.AutoInsuranceTypes;
import io.aster.policy.graphql.types.CreditCardTypes;
import io.aster.policy.graphql.types.EnterpriseLendingTypes;
import io.aster.policy.graphql.types.HealthcareTypes;
import io.aster.policy.graphql.types.LifeInsuranceTypes;
import io.aster.policy.graphql.types.LoanTypes;
import io.aster.policy.graphql.types.PolicyTypes;
import io.aster.policy.graphql.types.PersonalLendingTypes;
import io.smallrye.graphql.api.Context;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import graphql.GraphQLContext;
import graphql.GraphQLException;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

/**
 * GraphQL API for Policy Evaluation
 *
 * 提供GraphQL查询接口用于复杂策略评估
 * 支持类型安全的参数传递和结果返回
 */
@GraphQLApi
public class PolicyGraphQLResource {

    @Inject
    PolicyManagementService policyManagementService;

    @Inject
    PolicyQueryService policyQueryService;

    @Inject
    CacheManagementService cacheManagementService;

    @Inject
    InboxGuard inboxGuard;

    @jakarta.ws.rs.core.Context
    RoutingContext routingContext;

    private String tenantId() {
        // GraphQL 请求不经过 RESTEasy Reactive 的 JAX-RS 上下文，这里改用 Vert.x RoutingContext 获取 HTTP 头
        if (routingContext == null || routingContext.request() == null) {
            return "default";
        }
        String tenant = routingContext.request().getHeader("X-Tenant-Id");
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim();
    }

    // ==================== Life Insurance Queries ====================

    @Query("generateLifeQuote")
    @Description("生成人寿保险报价 / Generate life insurance quote")
    public Uni<LifeInsuranceTypes.Quote> generateLifeQuote(
            @NonNull @Description("申请人信息 / Applicant information")
            LifeInsuranceTypes.Applicant applicant,

            @NonNull @Description("保单请求信息 / Policy request information")
            LifeInsuranceTypes.PolicyRequest request
    ) {
        return policyQueryService.generateLifeQuote(tenantId(), applicant, request);
    }

    @Query("calculateLifeRiskScore")
    @Description("计算人寿保险风险评分 / Calculate life insurance risk score")
    public Uni<Integer> calculateLifeRiskScore(
            @NonNull @Description("申请人信息 / Applicant information")
            LifeInsuranceTypes.Applicant applicant
    ) {
        return policyQueryService.calculateLifeRiskScore(tenantId(), applicant);
    }

    // ==================== Auto Insurance Queries ====================

    @Query("generateAutoQuote")
    @Description("生成汽车保险报价 / Generate auto insurance quote")
    public Uni<AutoInsuranceTypes.PolicyQuote> generateAutoQuote(
            @NonNull @Description("驾驶员信息 / Driver information")
            AutoInsuranceTypes.Driver driver,

            @NonNull @Description("车辆信息 / Vehicle information")
            AutoInsuranceTypes.Vehicle vehicle,

            @NonNull @Description("保险类型 / Coverage type (Premium/Standard/Basic)")
            String coverageType
    ) {
        return policyQueryService.generateAutoQuote(tenantId(), driver, vehicle, coverageType);
    }

    // ==================== Healthcare Queries ====================

    @Query("checkServiceEligibility")
    @Description("检查医疗服务资格 / Check healthcare service eligibility")
    public Uni<HealthcareTypes.EligibilityCheck> checkServiceEligibility(
            @NonNull @Description("患者信息 / Patient information")
            HealthcareTypes.Patient patient,

            @NonNull @Description("服务信息 / Service information")
            HealthcareTypes.Service service
    ) {
        return policyQueryService.checkServiceEligibility(tenantId(), patient, service);
    }

    @Query("processClaim")
    @Description("处理医疗索赔 / Process healthcare claim")
    public Uni<HealthcareTypes.ClaimDecision> processClaim(
            @NonNull @Description("索赔信息 / Claim information")
            HealthcareTypes.Claim claim,

            @NonNull @Description("提供者信息 / Provider information")
            HealthcareTypes.Provider provider,

            @NonNull @Description("患者保障比例 / Patient coverage percentage")
            Integer patientCoverage
    ) {
        return policyQueryService.processClaim(tenantId(), claim, provider, patientCoverage);
    }

    // ==================== Loan Queries ====================

    @Query("evaluateLoanEligibility")
    @Description("评估贷款资格 / Evaluate loan eligibility")
    public Uni<LoanTypes.Decision> evaluateLoanEligibility(
            @NonNull @Description("贷款申请信息 / Loan application information")
            LoanTypes.Application application,

            @NonNull @Description("申请人信息 / Applicant profile")
            LoanTypes.Applicant applicant
    ) {
        return policyQueryService.evaluateLoanEligibility(tenantId(), application, applicant);
    }

    // ==================== Credit Card Queries ====================

    @Query("evaluateCreditCardApplication")
    @Description("评估信用卡申请 / Evaluate credit card application")
    public Uni<CreditCardTypes.ApprovalDecision> evaluateCreditCardApplication(
            @NonNull @Description("申请人信息 / Applicant information")
            CreditCardTypes.ApplicantInfo applicant,

            @NonNull @Description("财务历史 / Financial history")
            CreditCardTypes.FinancialHistory history,

            @NonNull @Description("信用卡产品 / Credit card offer")
            CreditCardTypes.CreditCardOffer offer
    ) {
        return policyQueryService.evaluateCreditCardApplication(tenantId(), applicant, history, offer);
    }

    // ==================== Enterprise Lending Queries ====================

    @Query("evaluateEnterpriseLoan")
    @Description("评估企业贷款 / Evaluate enterprise loan")
    public Uni<EnterpriseLendingTypes.LendingDecision> evaluateEnterpriseLoan(
            @NonNull @Description("企业基本信息 / Enterprise information")
            EnterpriseLendingTypes.EnterpriseInfo enterprise,

            @NonNull @Description("财务状况 / Financial position")
            EnterpriseLendingTypes.FinancialPosition position,

            @NonNull @Description("企业历史记录 / Business history")
            EnterpriseLendingTypes.BusinessHistory history,

            @NonNull @Description("贷款申请 / Loan application")
            EnterpriseLendingTypes.LoanApplication application
    ) {
        return policyQueryService.evaluateEnterpriseLoan(tenantId(), enterprise, position, history, application);
    }

    // ==================== Personal Lending Queries ====================

    @Query("evaluatePersonalLoan")
    @Description("评估个人贷款 / Evaluate personal loan")
    public Uni<PersonalLendingTypes.LoanDecision> evaluatePersonalLoan(
            @NonNull @Description("个人基本信息 / Personal information")
            PersonalLendingTypes.PersonalInfo personal,

            @NonNull @Description("收入状况 / Income profile")
            PersonalLendingTypes.IncomeProfile income,

            @NonNull @Description("信用状况 / Credit profile")
            PersonalLendingTypes.CreditProfile credit,

            @NonNull @Description("债务状况 / Debt profile")
            PersonalLendingTypes.DebtProfile debt,

            @NonNull @Description("贷款申请 / Loan request")
            PersonalLendingTypes.LoanRequest request
    ) {
        return policyQueryService.evaluatePersonalLoan(
            tenantId(),
            personal,
            income,
            credit,
            debt,
            request
        );
    }

    // ==================== Policy Management ====================

    @Query("getPolicy")
    @Description("根据ID获取策略 / Get policy by ID")
    public Uni<PolicyTypes.Policy> getPolicy(
            @NonNull @Description("策略ID / Policy identifier")
            String id
    ) {
        return policyManagementService.getPolicy(tenantId(), id);
    }

    @Query("listPolicies")
    @Description("列出所有策略 / List policies")
    public Uni<List<PolicyTypes.Policy>> listPolicies() {
        return policyManagementService.listPolicies(tenantId());
    }

    @Mutation("createPolicy")
    @Description("创建策略 / Create policy")
    public Uni<PolicyTypes.Policy> createPolicy(
            @NonNull @Description("策略输入 / Policy payload")
            PolicyTypes.PolicyInput input,
            Context graphQLContext
    ) {
        GraphQLContext graphqlJavaContext = null;
        if (graphQLContext != null) {
            try {
                graphqlJavaContext = graphQLContext.unwrap(GraphQLContext.class);
            } catch (RuntimeException ignored) {
                graphqlJavaContext = null;
            }
        }

        String idempotencyKey = extractHeader(graphqlJavaContext, "Idempotency-Key");
        if ((idempotencyKey == null || idempotencyKey.isBlank()) && routingContext != null && routingContext.request() != null) {
            idempotencyKey = routingContext.request().getHeader("Idempotency-Key");
        }
        String tenantFromHeader = extractHeader(graphqlJavaContext, "X-Tenant-Id");
        String effectiveTenantId = (tenantFromHeader == null || tenantFromHeader.isBlank())
            ? tenantId()
            : tenantFromHeader;

        final String resolvedIdempotencyKey = idempotencyKey;

        if (resolvedIdempotencyKey == null || resolvedIdempotencyKey.isBlank()) {
            return policyManagementService.createPolicy(effectiveTenantId, input);
        }

        return inboxGuard.tryAcquire(resolvedIdempotencyKey, "CREATE_POLICY", effectiveTenantId)
            .flatMap(acquired -> {
                if (!acquired) {
                    return Uni.createFrom().failure(
                        new GraphQLException("Duplicate request: " + resolvedIdempotencyKey)
                    );
                }
                return policyManagementService.createPolicy(effectiveTenantId, input);
            });
    }

    @Mutation("updatePolicy")
    @Description("更新策略 / Update policy")
    public Uni<PolicyTypes.Policy> updatePolicy(
            @NonNull @Description("策略ID / Policy identifier")
            String id,
            @NonNull @Description("策略输入 / Policy payload")
            PolicyTypes.PolicyInput input
    ) {
        return policyManagementService.updatePolicy(tenantId(), id, input);
    }

    @Mutation("deletePolicy")
    @Description("删除策略 / Delete policy")
    public Uni<Boolean> deletePolicy(
            @NonNull @Description("策略ID / Policy identifier")
            String id
    ) {
        return policyManagementService.deletePolicy(tenantId(), id);
    }

    // ==================== Cache Management Mutations ====================

    @Mutation("clearAllCache")
    @Description("清空所有策略缓存 / Clear all policy cache")
    public Uni<CacheOperationResult> clearAllCache() {
        return cacheManagementService.clearAllCache();
    }

    @Mutation("invalidateCache")
    @Description("使特定策略的缓存失效 / Invalidate cache for specific policy")
    public Uni<CacheOperationResult> invalidateCache(
            @Description("策略模块名称 / Policy module name")
            String policyModule,

            @Description("策略函数名称 / Policy function name")
            String policyFunction
    ) {
        return cacheManagementService.invalidateTenantCache(tenantId(), policyModule, policyFunction);
    }

    private String extractHeader(GraphQLContext context, String headerName) {
        if (context == null || headerName == null) {
            return null;
        }
        Object value = context.getOrDefault(headerName, null);
        if (value instanceof String str) {
            return str;
        }
        return null;
    }
}
