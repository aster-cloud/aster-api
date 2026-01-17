package io.aster.policy.graphql.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.policy.graphql.types.CreditCardTypes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CreditCardConverterTest {

    @Inject
    CreditCardConverter converter;

    @Test
    void testGetDomain() {
        assertThat(converter.getDomain()).isEqualTo("credit-card");
    }

    @Test
    void testToAsterContext_normalInput() {
        CreditCardTypes.ApplicantInfo applicant = new CreditCardTypes.ApplicantInfo(
            "APP-CC-01",
            29,
            85_000,
            760,
            2,
            1_200,
            "FULL_TIME",
            4
        );
        CreditCardTypes.FinancialHistory history = new CreditCardTypes.FinancialHistory(
            0,
            1,
            25,
            6,
            2
        );
        CreditCardTypes.CreditCardOffer offer = new CreditCardTypes.CreditCardOffer(
            "PREMIUM",
            25_000,
            true,
            199
        );

        Map<String, Object> context = converter.toAsterContext(
            new CreditCardConverter.CreditCardInput(applicant, history, offer),
            "tenant-cc"
        );

        assertThat(context).containsOnlyKeys("applicant", "history", "offer");
        assertThat(mapOf(context.get("applicant")))
            .containsEntry("applicantId", "APP-CC-01")
            .containsEntry("age", 29)
            .containsEntry("annualIncome", 85_000)
            .containsEntry("employmentStatus", "FULL_TIME");
        assertThat(mapOf(context.get("history")))
            .containsEntry("bankruptcyCount", 0)
            .containsEntry("latePayments", 1)
            .containsEntry("utilization", 25);
        assertThat(mapOf(context.get("offer")))
            .containsEntry("productType", "PREMIUM")
            .containsEntry("requestedLimit", 25_000)
            .containsEntry("hasRewards", true)
            .containsEntry("annualFee", 199);
    }

    @Test
    void testToGraphQLResponse_normalOutput() {
        CreditCardDecisionResult result = new CreditCardDecisionResult(
            true,
            "APPROVED",
            30_000,
            219,
            15,
            30_000,
            false,
            0
        );

        CreditCardTypes.ApprovalDecision decision = converter.toGraphQLResponse(result);

        assertThat(decision.approved).isTrue();
        assertThat(decision.reason).isEqualTo("APPROVED");
        assertThat(decision.approvedLimit).isEqualTo(30_000);
        assertThat(decision.interestRateAPR).isEqualTo(219);
        assertThat(decision.requiresDeposit).isFalse();
        assertThat(decision.depositAmount).isZero();
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

    private static final class CreditCardDecisionResult {
        public final Boolean approved;
        public final String reason;
        public final Integer approvedLimit;
        public final Integer interestRateAPR;
        public final Integer monthlyFee;
        public final Integer creditLine;
        public final Boolean requiresDeposit;
        public final Integer depositAmount;

        private CreditCardDecisionResult(
            Boolean approved,
            String reason,
            Integer approvedLimit,
            Integer interestRateAPR,
            Integer monthlyFee,
            Integer creditLine,
            Boolean requiresDeposit,
            Integer depositAmount
        ) {
            this.approved = approved;
            this.reason = reason;
            this.approvedLimit = approvedLimit;
            this.interestRateAPR = interestRateAPR;
            this.monthlyFee = monthlyFee;
            this.creditLine = creditLine;
            this.requiresDeposit = requiresDeposit;
            this.depositAmount = depositAmount;
        }
    }
}

