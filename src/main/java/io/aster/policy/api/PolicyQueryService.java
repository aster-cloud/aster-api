package io.aster.policy.api;

import io.aster.policy.graphql.converter.AutoInsuranceConverter;
import io.aster.policy.graphql.converter.CreditCardConverter;
import io.aster.policy.graphql.converter.EnterpriseLendingConverter;
import io.aster.policy.graphql.converter.HealthcareConverter;
import io.aster.policy.graphql.converter.LifeInsuranceConverter;
import io.aster.policy.graphql.converter.LoanConverter;
import io.aster.policy.graphql.converter.PersonalLendingConverter;
import io.aster.policy.graphql.types.AutoInsuranceTypes;
import io.aster.policy.graphql.types.CreditCardTypes;
import io.aster.policy.graphql.types.EnterpriseLendingTypes;
import io.aster.policy.graphql.types.HealthcareTypes;
import io.aster.policy.graphql.types.LifeInsuranceTypes;
import io.aster.policy.graphql.types.LoanTypes;
import io.aster.policy.graphql.types.PersonalLendingTypes;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * 策略查询服务，封装GraphQL查询对应的策略执行流程。
 */
@ApplicationScoped
public class PolicyQueryService {

    @Inject
    PolicyEvaluationService policyEvaluationService;

    @Inject
    LifeInsuranceConverter lifeInsuranceConverter;

    @Inject
    AutoInsuranceConverter autoInsuranceConverter;

    @Inject
    HealthcareConverter healthcareConverter;

    @Inject
    LoanConverter loanConverter;

    @Inject
    CreditCardConverter creditCardConverter;

    @Inject
    EnterpriseLendingConverter enterpriseLendingConverter;

    @Inject
    PersonalLendingConverter personalLendingConverter;

    // 无参构造器供CDI使用
    public PolicyQueryService() {
    }

    // 包私有构造器供测试使用
    PolicyQueryService(
        PolicyEvaluationService policyEvaluationService,
        LifeInsuranceConverter lifeInsuranceConverter,
        AutoInsuranceConverter autoInsuranceConverter,
        HealthcareConverter healthcareConverter,
        LoanConverter loanConverter,
        CreditCardConverter creditCardConverter,
        EnterpriseLendingConverter enterpriseLendingConverter,
        PersonalLendingConverter personalLendingConverter
    ) {
        this.policyEvaluationService = policyEvaluationService;
        this.lifeInsuranceConverter = lifeInsuranceConverter;
        this.autoInsuranceConverter = autoInsuranceConverter;
        this.healthcareConverter = healthcareConverter;
        this.loanConverter = loanConverter;
        this.creditCardConverter = creditCardConverter;
        this.enterpriseLendingConverter = enterpriseLendingConverter;
        this.personalLendingConverter = personalLendingConverter;
    }

    /**
     * 生成人寿保险报价。
     */
    public Uni<LifeInsuranceTypes.Quote> generateLifeQuote(
            String tenantId,
            LifeInsuranceTypes.Applicant applicant,
            LifeInsuranceTypes.PolicyRequest request
    ) {
        LifeInsuranceConverter.LifeQuoteInput input = new LifeInsuranceConverter.LifeQuoteInput(applicant, request);
        Map<String, Object> lifeContext = lifeInsuranceConverter.toAsterContext(input, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.insurance.life",
                "generateLifeQuote",
                buildContext(lifeContext.get("applicant"), lifeContext.get("request"))
            )
            .onItem().transform(result -> lifeInsuranceConverter.toGraphQLResponse(result.getResult()));
    }

    /**
     * 计算人寿保险风险评分。
     */
    public Uni<Integer> calculateLifeRiskScore(String tenantId, LifeInsuranceTypes.Applicant applicant) {
        Map<String, Object> applicantContext = lifeInsuranceConverter.toApplicantContext(applicant);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.insurance.life",
                "calculateRiskScore",
                buildContext(applicantContext)
            )
            .onItem().transform(result -> (Integer) result.getResult());
    }

    /**
     * 生成汽车保险报价。
     */
    public Uni<AutoInsuranceTypes.PolicyQuote> generateAutoQuote(
            String tenantId,
            AutoInsuranceTypes.Driver driver,
            AutoInsuranceTypes.Vehicle vehicle,
            String coverageType
    ) {
        AutoInsuranceConverter.AutoQuoteInput input = new AutoInsuranceConverter.AutoQuoteInput(driver, vehicle);
        Map<String, Object> autoContext = autoInsuranceConverter.toAsterContext(input, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.insurance.auto",
                "generateAutoQuote",
                buildContext(
                    autoContext.get("driver"),
                    autoContext.get("vehicle"),
                    coverageType
                )
            )
            .onItem().transform(result -> autoInsuranceConverter.toGraphQLResponse(result.getResult()));
    }

    /**
     * 检查医疗服务资格。
     */
    public Uni<HealthcareTypes.EligibilityCheck> checkServiceEligibility(
            String tenantId,
            HealthcareTypes.Patient patient,
            HealthcareTypes.Service service
    ) {
        HealthcareConverter.HealthcareInput eligibilityInput =
            new HealthcareConverter.EligibilityInput(patient, service);
        Map<String, Object> healthcareContext = healthcareConverter.toAsterContext(eligibilityInput, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.healthcare.eligibility",
                "checkServiceEligibility",
                buildContext(
                    healthcareContext.get("patient"),
                    healthcareContext.get("service")
                )
            )
            .onItem().transform(result ->
                (HealthcareTypes.EligibilityCheck) healthcareConverter.toGraphQLResponse(result.getResult()));
    }

    /**
     * 处理医疗索赔。
     */
    public Uni<HealthcareTypes.ClaimDecision> processClaim(
            String tenantId,
            HealthcareTypes.Claim claim,
            HealthcareTypes.Provider provider,
            Integer patientCoverage
    ) {
        HealthcareConverter.HealthcareInput claimInput =
            new HealthcareConverter.ClaimInput(claim, provider, patientCoverage);
        Map<String, Object> claimContext = healthcareConverter.toAsterContext(claimInput, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.healthcare.claims",
                "processClaim",
                buildContext(
                    claimContext.get("claim"),
                    claimContext.get("provider"),
                    claimContext.get("patientCoverage")
                )
            )
            .onItem().transform(result ->
                (HealthcareTypes.ClaimDecision) healthcareConverter.toGraphQLResponse(result.getResult()));
    }

    /**
     * 评估贷款资格。
     */
    public Uni<LoanTypes.Decision> evaluateLoanEligibility(
            String tenantId,
            LoanTypes.Application application,
            LoanTypes.Applicant applicant
    ) {
        LoanConverter.LoanInputWrapper loanInput = new LoanConverter.LoanInputWrapper(application, applicant);
        Map<String, Object> loanContext = loanConverter.toAsterContext(loanInput, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.finance.loan",
                "evaluateLoanEligibility",
                buildContext(
                    loanContext.get("application"),
                    loanContext.get("applicant")
                )
            )
            .onItem().transform(result -> loanConverter.toGraphQLResponse(result.getResult()));
    }

    /**
     * 评估信用卡申请。
     */
    public Uni<CreditCardTypes.ApprovalDecision> evaluateCreditCardApplication(
            String tenantId,
            CreditCardTypes.ApplicantInfo applicant,
            CreditCardTypes.FinancialHistory history,
            CreditCardTypes.CreditCardOffer offer
    ) {
        CreditCardConverter.CreditCardInput input =
            new CreditCardConverter.CreditCardInput(applicant, history, offer);
        Map<String, Object> creditContext = creditCardConverter.toAsterContext(input, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.finance.creditcard",
                "evaluateCreditCardApplication",
                buildContext(
                    creditContext.get("applicant"),
                    creditContext.get("history"),
                    creditContext.get("offer")
                )
            )
            .onItem().transform(result -> creditCardConverter.toGraphQLResponse(result.getResult()));
    }

    /**
     * 评估企业贷款。
     */
    public Uni<EnterpriseLendingTypes.LendingDecision> evaluateEnterpriseLoan(
            String tenantId,
            EnterpriseLendingTypes.EnterpriseInfo enterprise,
            EnterpriseLendingTypes.FinancialPosition position,
            EnterpriseLendingTypes.BusinessHistory history,
            EnterpriseLendingTypes.LoanApplication application
    ) {
        EnterpriseLendingConverter.EnterpriseInput input = new EnterpriseLendingConverter.EnterpriseInput(
            enterprise,
            position,
            history,
            application
        );
        Map<String, Object> enterpriseContext = enterpriseLendingConverter.toAsterContext(input, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.finance.enterprise_lending",
                "evaluateEnterpriseLoan",
                buildContext(
                    enterpriseContext.get("enterprise"),
                    enterpriseContext.get("position"),
                    enterpriseContext.get("history"),
                    enterpriseContext.get("application")
                )
            )
            .onItem().transform(result -> enterpriseLendingConverter.toGraphQLResponse(result.getResult()));
    }

    /**
     * 评估个人贷款。
     */
    public Uni<PersonalLendingTypes.LoanDecision> evaluatePersonalLoan(
            String tenantId,
            PersonalLendingTypes.PersonalInfo personal,
            PersonalLendingTypes.IncomeProfile income,
            PersonalLendingTypes.CreditProfile credit,
            PersonalLendingTypes.DebtProfile debt,
            PersonalLendingTypes.LoanRequest request
    ) {
        PersonalLendingConverter.PersonalLoanInput input = new PersonalLendingConverter.PersonalLoanInput(
            personal,
            income,
            credit,
            debt,
            request
        );
        Map<String, Object> personalContext = personalLendingConverter.toAsterContext(input, tenantId);
        return policyEvaluationService.evaluatePolicy(
                tenantId,
                "aster.finance.personal_lending",
                "evaluatePersonalLoan",
                buildContext(
                    personalContext.get("personal"),
                    personalContext.get("income"),
                    personalContext.get("credit"),
                    personalContext.get("debt"),
                    personalContext.get("request")
                )
            )
            .onItem().transform(result -> personalLendingConverter.toGraphQLResponse(result.getResult()));
    }

    private Object[] buildContext(Object... args) {
        return args == null ? new Object[0] : args;
    }
}
