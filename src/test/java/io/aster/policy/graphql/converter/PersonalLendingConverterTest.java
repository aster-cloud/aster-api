package io.aster.policy.graphql.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.policy.graphql.types.PersonalLendingTypes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PersonalLendingConverterTest {

    @Inject
    PersonalLendingConverter converter;

    @Test
    void testGetDomain() {
        assertThat(converter.getDomain()).isEqualTo("personal-lending");
    }

    @Test
    void testToAsterContext_normalInput() {
        PersonalLendingTypes.PersonalInfo personal = new PersonalLendingTypes.PersonalInfo(
            "PL-001",
            33,
            "BACHELOR",
            "FULL_TIME",
            "ENGINEER",
            6,
            48,
            "MARRIED",
            2
        );
        PersonalLendingTypes.IncomeProfile income = new PersonalLendingTypes.IncomeProfile(
            9_500,
            500,
            2_500,
            0,
            "STABLE",
            5
        );
        PersonalLendingTypes.CreditProfile credit = new PersonalLendingTypes.CreditProfile(
            785,
            120,
            1,
            3,
            22,
            0,
            0,
            0,
            1
        );
        PersonalLendingTypes.DebtProfile debt = new PersonalLendingTypes.DebtProfile(
            1_800,
            0,
            350,
            250,
            150,
            200,
            18_000
        );
        PersonalLendingTypes.LoanRequest request = new PersonalLendingTypes.LoanRequest(
            45_000,
            "HOME_IMPROVEMENT",
            84,
            5_000,
            10_000
        );

        Map<String, Object> context = converter.toAsterContext(
            new PersonalLendingConverter.PersonalLoanInput(personal, income, credit, debt, request),
            "tenant-pl"
        );

        assertThat(context).containsOnlyKeys("personal", "income", "credit", "debt", "request");
        assertThat(mapOf(context.get("personal")))
            .containsEntry("applicantId", "PL-001")
            .containsEntry("dependents", 2)
            .containsEntry("employmentStatus", "FULL_TIME");
        assertThat(mapOf(context.get("income")))
            .containsEntry("monthlyIncome", 9_500)
            .containsEntry("incomeStability", "STABLE");
        assertThat(mapOf(context.get("credit")))
            .containsEntry("creditScore", 785)
            .containsEntry("creditUtilization", 22);
        Map<String, Object> debtContext = mapOf(context.get("debt"));
        assertThat(debtContext)
            .containsEntry("totalMonthlyDebt", 1_800)
            .doesNotContainKey("totalOutstandingDebt");
        assertThat(mapOf(context.get("request")))
            .containsEntry("requestedAmount", 45_000)
            .containsEntry("purpose", "HOME_IMPROVEMENT");
    }

    @Test
    void testToGraphQLResponse_normalOutput() {
        PersonalLoanDecisionResult result = new PersonalLoanDecisionResult(
            true,
            40_000,
            315,
            72,
            780,
            5_000,
            "Require automatic payments",
            "LOW",
            890,
            "A01",
            "Consider premium credit card"
        );

        PersonalLendingTypes.LoanDecision decision = converter.toGraphQLResponse(result);

        assertThat(decision.approved).isTrue();
        assertThat(decision.approvedAmount).isEqualTo(40_000);
        assertThat(decision.interestRateBps).isEqualTo(315);
        assertThat(decision.monthlyPayment).isEqualTo(780);
        assertThat(decision.conditions).contains("automatic");
        assertThat(decision.recommendations).contains("credit card");
    }

    @Test
    void testToAsterContext_nullInput() {
        assertThatThrownBy(() -> converter.toAsterContext(null, "tenant"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testToGraphQLResponse_nullResult() {
        assertThat(converter.toGraphQLResponse(null)).isNull();
    }

    @Test
    void testToGraphQLResponse_invalidPayload() {
        assertThatThrownBy(() -> converter.toGraphQLResponse(new Object()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to convert");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class PersonalLoanDecisionResult {
        public final Boolean approved;
        public final Integer approvedAmount;
        public final Integer interestRateBps;
        public final Integer termMonths;
        public final Integer monthlyPayment;
        public final Integer downPaymentRequired;
        public final String conditions;
        public final String riskLevel;
        public final Integer decisionScore;
        public final String reasonCode;
        public final String recommendations;

        private PersonalLoanDecisionResult(
            Boolean approved,
            Integer approvedAmount,
            Integer interestRateBps,
            Integer termMonths,
            Integer monthlyPayment,
            Integer downPaymentRequired,
            String conditions,
            String riskLevel,
            Integer decisionScore,
            String reasonCode,
            String recommendations
        ) {
            this.approved = approved;
            this.approvedAmount = approvedAmount;
            this.interestRateBps = interestRateBps;
            this.termMonths = termMonths;
            this.monthlyPayment = monthlyPayment;
            this.downPaymentRequired = downPaymentRequired;
            this.conditions = conditions;
            this.riskLevel = riskLevel;
            this.decisionScore = decisionScore;
            this.reasonCode = reasonCode;
            this.recommendations = recommendations;
        }
    }
}
