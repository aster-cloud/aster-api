package io.aster.policy.api;

import io.aster.policy.api.model.PolicyEvaluationResult;
import io.aster.policy.graphql.converter.*;
import io.aster.policy.graphql.types.*;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PolicyQueryService 单元测试，覆盖七大领域查询逻辑与转换器协同。
 */
@ExtendWith(MockitoExtension.class)
class PolicyQueryServiceTest {

    private PolicyQueryService policyQueryService;

    @Mock
    private PolicyEvaluationService policyEvaluationService;

    @Mock
    private LifeInsuranceConverter lifeInsuranceConverter;

    @Mock
    private AutoInsuranceConverter autoInsuranceConverter;

    @Mock
    private HealthcareConverter healthcareConverter;

    @Mock
    private LoanConverter loanConverter;

    @Mock
    private CreditCardConverter creditCardConverter;

    @Mock
    private EnterpriseLendingConverter enterpriseLendingConverter;

    @Mock
    private PersonalLendingConverter personalLendingConverter;

    @BeforeEach
    void setUp() {
        policyQueryService = new PolicyQueryService(
            policyEvaluationService,
            lifeInsuranceConverter,
            autoInsuranceConverter,
            healthcareConverter,
            loanConverter,
            creditCardConverter,
            enterpriseLendingConverter,
            personalLendingConverter
        );
    }

    @Test
    void testGenerateLifeQuote_Success() {
        // Given
        LifeInsuranceTypes.Applicant applicant = createLifeApplicant();
        LifeInsuranceTypes.PolicyRequest request = createLifePolicyRequest();
        LifeInsuranceTypes.Quote expectedQuote = new LifeInsuranceTypes.Quote(true, "Approved", 250, 500000, 20);

        when(lifeInsuranceConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("applicant", new Object(), "request", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.insurance.life"), eq("generateLifeQuote"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(lifeInsuranceConverter.toGraphQLResponse(any())).thenReturn(expectedQuote);

        // When
        Uni<LifeInsuranceTypes.Quote> result = policyQueryService.generateLifeQuote("default", applicant, request);

        // Then
        LifeInsuranceTypes.Quote quote = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(quote).isNotNull();
        assertThat(quote.approved).isTrue();
        assertThat(quote.monthlyPremium).isEqualTo(250);
    }

    @Test
    void testGenerateLifeQuote_EvaluationFails() {
        // Given
        LifeInsuranceTypes.Applicant applicant = createLifeApplicant();
        LifeInsuranceTypes.PolicyRequest request = createLifePolicyRequest();

        when(lifeInsuranceConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("applicant", new Object(), "request", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.insurance.life"), eq("generateLifeQuote"), any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("evaluation failed")));

        // When
        UniAssertSubscriber<LifeInsuranceTypes.Quote> subscriber = policyQueryService
            .generateLifeQuote("default", applicant, request)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("evaluation failed");
    }

    @Test
    void testCalculateLifeRiskScore_Success() {
        // Given
        LifeInsuranceTypes.Applicant applicant = createLifeApplicant();
        Integer expectedScore = 75;

        when(lifeInsuranceConverter.toApplicantContext(any())).thenReturn(java.util.Map.of("age", 35));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.insurance.life"), eq("calculateRiskScore"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(expectedScore, 123, false)));

        // When
        Uni<Integer> result = policyQueryService.calculateLifeRiskScore("default", applicant);

        // Then
        Integer score = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(score).isEqualTo(75);
    }

    @Test
    void testCalculateLifeRiskScore_EvaluationFails() {
        // Given
        LifeInsuranceTypes.Applicant applicant = createLifeApplicant();

        when(lifeInsuranceConverter.toApplicantContext(any()))
            .thenReturn(java.util.Map.of("age", 35));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.insurance.life"), eq("calculateRiskScore"), any()))
            .thenReturn(Uni.createFrom().failure(new IllegalStateException("risk fail")));

        // When
        UniAssertSubscriber<Integer> subscriber = policyQueryService.calculateLifeRiskScore("default", applicant)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("risk fail");
    }

    @Test
    void testGenerateAutoQuote_Success() {
        // Given
        AutoInsuranceTypes.Driver driver = createAutoDriver();
        AutoInsuranceTypes.Vehicle vehicle = createAutoVehicle();
        AutoInsuranceTypes.PolicyQuote expectedQuote = new AutoInsuranceTypes.PolicyQuote(true, "Approved", 150, 1000, 50000);

        when(autoInsuranceConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("driver", new Object(), "vehicle", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.insurance.auto"), eq("generateAutoQuote"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(autoInsuranceConverter.toGraphQLResponse(any())).thenReturn(expectedQuote);

        // When
        Uni<AutoInsuranceTypes.PolicyQuote> result = policyQueryService.generateAutoQuote("default", driver, vehicle, "Premium");

        // Then
        AutoInsuranceTypes.PolicyQuote quote = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(quote).isNotNull();
        assertThat(quote.approved).isTrue();
        assertThat(quote.monthlyPremium).isEqualTo(150);
    }

    @Test
    void testCheckServiceEligibility_Success() {
        // Given
        HealthcareTypes.Patient patient = createHealthcarePatient();
        HealthcareTypes.Service service = createHealthcareService();
        HealthcareTypes.EligibilityCheck expectedCheck = new HealthcareTypes.EligibilityCheck(true, "Eligible", 80, 200, false);

        when(healthcareConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("patient", new Object(), "service", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.healthcare.eligibility"), eq("checkServiceEligibility"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(healthcareConverter.toGraphQLResponse(any())).thenReturn(expectedCheck);

        // When
        Uni<HealthcareTypes.EligibilityCheck> result = policyQueryService.checkServiceEligibility("default", patient, service);

        // Then
        HealthcareTypes.EligibilityCheck check = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(check).isNotNull();
        assertThat(check.eligible).isTrue();
        assertThat(check.coveragePercent).isEqualTo(80);
    }

    @Test
    void testCheckServiceEligibility_EvaluationFails() {
        // Given
        HealthcareTypes.Patient patient = createHealthcarePatient();
        HealthcareTypes.Service service = createHealthcareService();

        when(healthcareConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("patient", new Object(), "service", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.healthcare.eligibility"), eq("checkServiceEligibility"), any()))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("eligibility error")));

        // When
        UniAssertSubscriber<HealthcareTypes.EligibilityCheck> subscriber = policyQueryService
            .checkServiceEligibility("default", patient, service)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("eligibility error");
    }

    @Test
    void testProcessClaim_Success() {
        // Given
        HealthcareTypes.Claim claim = createHealthcareClaim();
        HealthcareTypes.Provider provider = createHealthcareProvider();
        HealthcareTypes.ClaimDecision expectedDecision = new HealthcareTypes.ClaimDecision(true, "Approved", 1500, false, null);

        when(healthcareConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("claim", new Object(), "provider", new Object(), "patientCoverage", 80));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.healthcare.claims"), eq("processClaim"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(healthcareConverter.toGraphQLResponse(any())).thenReturn(expectedDecision);

        // When
        Uni<HealthcareTypes.ClaimDecision> result = policyQueryService.processClaim("default", claim, provider, 80);

        // Then
        HealthcareTypes.ClaimDecision decision = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(decision).isNotNull();
        assertThat(decision.approved).isTrue();
        assertThat(decision.approvedAmount).isEqualTo(1500);
    }

    @Test
    void testProcessClaim_EvaluationFails() {
        // Given
        HealthcareTypes.Claim claim = createHealthcareClaim();
        HealthcareTypes.Provider provider = createHealthcareProvider();

        when(healthcareConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("claim", new Object(), "provider", new Object(), "patientCoverage", 80));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.healthcare.claims"), eq("processClaim"), any()))
            .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("claim failure")));

        // When
        UniAssertSubscriber<HealthcareTypes.ClaimDecision> subscriber = policyQueryService
            .processClaim("default", claim, provider, 80)
            .subscribe().withSubscriber(UniAssertSubscriber.create());

        // Then
        subscriber.awaitFailure();

        Throwable failure = subscriber.getFailure();
        assertThat(failure)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("claim failure");
    }

    @Test
    void testEvaluateLoanEligibility_Success() {
        // Given
        LoanTypes.Application application = createLoanApplication();
        LoanTypes.Applicant applicant = createLoanApplicant();
        LoanTypes.Decision expectedDecision = new LoanTypes.Decision(true, "Approved", 50000, 450, 360);

        when(loanConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("application", new Object(), "applicant", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.finance.loan"), eq("evaluateLoanEligibility"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(loanConverter.toGraphQLResponse(any())).thenReturn(expectedDecision);

        // When
        Uni<LoanTypes.Decision> result = policyQueryService.evaluateLoanEligibility("default", application, applicant);

        // Then
        LoanTypes.Decision decision = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(decision).isNotNull();
        assertThat(decision.approved).isTrue();
        assertThat(decision.maxApprovedAmount).isEqualTo(50000);
    }

    @Test
    void testEvaluateCreditCardApplication_Success() {
        // Given
        CreditCardTypes.ApplicantInfo applicant = createCreditCardApplicant();
        CreditCardTypes.FinancialHistory history = createCreditCardHistory();
        CreditCardTypes.CreditCardOffer offer = createCreditCardOffer();
        CreditCardTypes.ApprovalDecision expectedDecision = new CreditCardTypes.ApprovalDecision(
            true, "Approved", 10000, 1599, 0, 10000, false, 0
        );

        when(creditCardConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("applicant", new Object(), "history", new Object(), "offer", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.finance.creditcard"), eq("evaluateCreditCardApplication"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(creditCardConverter.toGraphQLResponse(any())).thenReturn(expectedDecision);

        // When
        Uni<CreditCardTypes.ApprovalDecision> result = policyQueryService.evaluateCreditCardApplication("default", applicant, history, offer);

        // Then
        CreditCardTypes.ApprovalDecision decision = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(decision).isNotNull();
        assertThat(decision.approved).isTrue();
        assertThat(decision.approvedLimit).isEqualTo(10000);
    }

    @Test
    void testEvaluateEnterpriseLoan_Success() {
        // Given
        EnterpriseLendingTypes.EnterpriseInfo enterprise = createEnterpriseInfo();
        EnterpriseLendingTypes.FinancialPosition position = createFinancialPosition();
        EnterpriseLendingTypes.BusinessHistory history = createBusinessHistory();
        EnterpriseLendingTypes.LoanApplication application = createEnterpriseLoanApplication();
        EnterpriseLendingTypes.LendingDecision expectedDecision = new EnterpriseLendingTypes.LendingDecision(
            true, 1000000, 350, 60, 500000, "None", "Low", 90, "APPROVED", "Strong financials"
        );

        when(enterpriseLendingConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("enterprise", new Object(), "position", new Object(), "history", new Object(), "application", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.finance.enterprise_lending"), eq("evaluateEnterpriseLoan"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(enterpriseLendingConverter.toGraphQLResponse(any())).thenReturn(expectedDecision);

        // When
        Uni<EnterpriseLendingTypes.LendingDecision> result = policyQueryService.evaluateEnterpriseLoan("default", enterprise, position, history, application);

        // Then
        EnterpriseLendingTypes.LendingDecision decision = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(decision).isNotNull();
        assertThat(decision.approved).isTrue();
        assertThat(decision.approvedAmount).isEqualTo(1000000);
    }

    @Test
    void testEvaluatePersonalLoan_Success() {
        // Given
        PersonalLendingTypes.PersonalInfo personal = createPersonalInfo();
        PersonalLendingTypes.IncomeProfile income = createIncomeProfile();
        PersonalLendingTypes.CreditProfile credit = createCreditProfile();
        PersonalLendingTypes.DebtProfile debt = createDebtProfile();
        PersonalLendingTypes.LoanRequest request = createPersonalLoanRequest();
        PersonalLendingTypes.LoanDecision expectedDecision = new PersonalLendingTypes.LoanDecision(
            true, 25000, 580, 60, 450, 0, "None", "Low", 85, "APPROVED", "Good credit"
        );

        when(personalLendingConverter.toAsterContext(any(), eq("default")))
            .thenReturn(java.util.Map.of("personal", new Object(), "income", new Object(), "credit", new Object(), "debt", new Object(), "request", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("default"), eq("aster.finance.personal_lending"), eq("evaluatePersonalLoan"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(personalLendingConverter.toGraphQLResponse(any())).thenReturn(expectedDecision);

        // When
        Uni<PersonalLendingTypes.LoanDecision> result = policyQueryService.evaluatePersonalLoan("default", personal, income, credit, debt, request);

        // Then
        PersonalLendingTypes.LoanDecision decision = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(decision).isNotNull();
        assertThat(decision.approved).isTrue();
        assertThat(decision.approvedAmount).isEqualTo(25000);
    }

    @Test
    void testEvaluatePersonalLoan_NullTenant_DefaultsToDefault() {
        // Given
        PersonalLendingTypes.PersonalInfo personal = createPersonalInfo();
        PersonalLendingTypes.IncomeProfile income = createIncomeProfile();
        PersonalLendingTypes.CreditProfile credit = createCreditProfile();
        PersonalLendingTypes.DebtProfile debt = createDebtProfile();
        PersonalLendingTypes.LoanRequest request = createPersonalLoanRequest();
        PersonalLendingTypes.LoanDecision expectedDecision = new PersonalLendingTypes.LoanDecision(
            true, 25000, 580, 60, 450, 0, "None", "Low", 85, "APPROVED", "Good credit"
        );

        when(personalLendingConverter.toAsterContext(any(), eq(null)))
            .thenReturn(java.util.Map.of("personal", new Object(), "income", new Object(), "credit", new Object(), "debt", new Object(), "request", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq(null), eq("aster.finance.personal_lending"), eq("evaluatePersonalLoan"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(personalLendingConverter.toGraphQLResponse(any())).thenReturn(expectedDecision);

        // When
        Uni<PersonalLendingTypes.LoanDecision> result = policyQueryService.evaluatePersonalLoan(null, personal, income, credit, debt, request);

        // Then
        PersonalLendingTypes.LoanDecision decision = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(decision).isNotNull();
    }

    @Test
    void testEvaluatePersonalLoan_BlankTenant_DefaultsToDefault() {
        // Given
        PersonalLendingTypes.PersonalInfo personal = createPersonalInfo();
        PersonalLendingTypes.IncomeProfile income = createIncomeProfile();
        PersonalLendingTypes.CreditProfile credit = createCreditProfile();
        PersonalLendingTypes.DebtProfile debt = createDebtProfile();
        PersonalLendingTypes.LoanRequest request = createPersonalLoanRequest();
        PersonalLendingTypes.LoanDecision expectedDecision = new PersonalLendingTypes.LoanDecision(
            true, 25000, 580, 60, 450, 0, "None", "Low", 85, "APPROVED", "Good credit"
        );

        when(personalLendingConverter.toAsterContext(any(), eq("  ")))
            .thenReturn(java.util.Map.of("personal", new Object(), "income", new Object(), "credit", new Object(), "debt", new Object(), "request", new Object()));
        when(policyEvaluationService.evaluatePolicy(eq("  "), eq("aster.finance.personal_lending"), eq("evaluatePersonalLoan"), any()))
            .thenReturn(Uni.createFrom().item(new PolicyEvaluationResult(new Object(), 123, false)));
        when(personalLendingConverter.toGraphQLResponse(any())).thenReturn(expectedDecision);

        // When
        Uni<PersonalLendingTypes.LoanDecision> result = policyQueryService.evaluatePersonalLoan("  ", personal, income, credit, debt, request);

        // Then
        PersonalLendingTypes.LoanDecision decision = result.subscribe().withSubscriber(UniAssertSubscriber.create())
            .awaitItem()
            .getItem();

        assertThat(decision).isNotNull();
    }

    // Helper methods to create test data

    private LifeInsuranceTypes.Applicant createLifeApplicant() {
        return new LifeInsuranceTypes.Applicant("app-1", 35, "M", false, 25, "engineer", 90);
    }

    private LifeInsuranceTypes.PolicyRequest createLifePolicyRequest() {
        return new LifeInsuranceTypes.PolicyRequest(500000, 20, "term");
    }

    private AutoInsuranceTypes.Driver createAutoDriver() {
        return new AutoInsuranceTypes.Driver("driver-1", 28, 5, 0, 0, 750);
    }

    private AutoInsuranceTypes.Vehicle createAutoVehicle() {
        return new AutoInsuranceTypes.Vehicle("VIN123", 2020, "Toyota", "Camry", 25000, 5);
    }

    private HealthcareTypes.Patient createHealthcarePatient() {
        return new HealthcareTypes.Patient("patient-1", 40, "PPO", true, 1, 0);
    }

    private HealthcareTypes.Service createHealthcareService() {
        return new HealthcareTypes.Service("SVC001", "MRI Scan", 1000, false);
    }

    private HealthcareTypes.Claim createHealthcareClaim() {
        return new HealthcareTypes.Claim("claim-1", 2000, "2025-01-15", "Radiology", "M79.3", true, "patient-1", "provider-1");
    }

    private HealthcareTypes.Provider createHealthcareProvider() {
        return new HealthcareTypes.Provider("provider-1", true, 95, "Radiology");
    }

    private LoanTypes.Application createLoanApplication() {
        return new LoanTypes.Application("loan-1", "applicant-1", 50000, "HOME", 360);
    }

    private LoanTypes.Applicant createLoanApplicant() {
        LoanTypes.Applicant applicant = new LoanTypes.Applicant();
        applicant.age = 30;
        applicant.creditScore = 720;
        applicant.annualIncome = 80000;
        applicant.existingDebtMonthly = 1500;
        applicant.yearsEmployed = 5;
        return applicant;
    }

    private CreditCardTypes.ApplicantInfo createCreditCardApplicant() {
        return new CreditCardTypes.ApplicantInfo("app-1", 28, 60000, 720, 2, 1200, "employed", 3);
    }

    private CreditCardTypes.FinancialHistory createCreditCardHistory() {
        return new CreditCardTypes.FinancialHistory(0, 0, 30, 36, 1);
    }

    private CreditCardTypes.CreditCardOffer createCreditCardOffer() {
        return new CreditCardTypes.CreditCardOffer("rewards", 10000, true, 95);
    }

    private EnterpriseLendingTypes.EnterpriseInfo createEnterpriseInfo() {
        return new EnterpriseLendingTypes.EnterpriseInfo("ent-1", "Acme Corp", "Tech", 10, 250, 5000000, 15, 12);
    }

    private EnterpriseLendingTypes.FinancialPosition createFinancialPosition() {
        return new EnterpriseLendingTypes.FinancialPosition(10000000, 3000000, 4000000, 1500000, 6000000, 1000000, 2000000);
    }

    private EnterpriseLendingTypes.BusinessHistory createBusinessHistory() {
        return new EnterpriseLendingTypes.BusinessHistory(3, 0, 0, 40, 500000, 5);
    }

    private EnterpriseLendingTypes.LoanApplication createEnterpriseLoanApplication() {
        return new EnterpriseLendingTypes.LoanApplication(1000000, "expansion", 60, 500000, 2);
    }

    private PersonalLendingTypes.PersonalInfo createPersonalInfo() {
        return new PersonalLendingTypes.PersonalInfo("person-1", 32, "Bachelor", "employed", "Engineer", 5, 24, "married", 2);
    }

    private PersonalLendingTypes.IncomeProfile createIncomeProfile() {
        return new PersonalLendingTypes.IncomeProfile(5000, 500, 3000, 0, "stable", 5);
    }

    private PersonalLendingTypes.CreditProfile createCreditProfile() {
        return new PersonalLendingTypes.CreditProfile(720, 60, 2, 3, 25, 0, 0, 0, 1);
    }

    private PersonalLendingTypes.DebtProfile createDebtProfile() {
        return new PersonalLendingTypes.DebtProfile(1200, 800, 200, 150, 50, 0, 25000);
    }

    private PersonalLendingTypes.LoanRequest createPersonalLoanRequest() {
        return new PersonalLendingTypes.LoanRequest(25000, "debt_consolidation", 60, 0, 0);
    }
}
