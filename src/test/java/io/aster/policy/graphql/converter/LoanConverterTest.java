package io.aster.policy.graphql.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.policy.graphql.types.LoanTypes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LoanConverterTest {

    @Inject
    LoanConverter converter;

    @Test
    void testGetDomain() {
        assertThat(converter.getDomain()).isEqualTo("loans");
    }

    @Test
    void testToAsterContext_normalInput() {
        LoanTypes.Application application = new LoanTypes.Application(
            "LN-001",
            "AP-001",
            150_000,
            "HOME",
            240
        );
        LoanTypes.Applicant applicant = new LoanTypes.Applicant(
            "AP-001",
            35,
            120_000,
            780,
            450,
            8
        );

        Map<String, Object> context = converter.toAsterContext(
            new LoanConverter.LoanInputWrapper(application, applicant),
            "tenant-alpha"
        );

        assertThat(context).containsOnlyKeys("application", "applicant");
        Map<String, Object> applicationContext = mapOf(context.get("application"));
        assertThat(applicationContext)
            .doesNotContainKey("loanId")
            .containsEntry("applicantId", "AP-001")
            .containsEntry("amount", 150_000)
            .containsEntry("termMonths", 240)
            .containsEntry("purpose", "HOME");
        assertThat(mapOf(context.get("applicant")))
            .containsEntry("age", 35)
            .containsEntry("creditScore", 780)
            .containsEntry("annualIncome", 120_000)
            .containsEntry("monthlyDebt", 450)
            .containsEntry("yearsEmployed", 8);
    }

    @Test
    void testToGraphQLResponse_normalOutput() {
        LoanDecisionAsterResult asterResult = new LoanDecisionAsterResult(
            true,
            "APPROVED",
            160_000,
            425,
            180
        );

        LoanTypes.Decision decision = converter.toGraphQLResponse(asterResult);

        assertThat(decision.approved).isTrue();
        assertThat(decision.reason).isEqualTo("APPROVED");
        assertThat(decision.maxApprovedAmount).isEqualTo(160_000);
        assertThat(decision.interestRateBps).isEqualTo(425);
        assertThat(decision.termMonths).isEqualTo(180);
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
        Object invalidResult = new Object();
        assertThatThrownBy(() -> converter.toGraphQLResponse(invalidResult))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to convert");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class LoanDecisionAsterResult {
        public final Boolean approved;
        public final String reason;
        public final Integer approvedAmount;
        public final Integer interestRateBps;
        public final Integer termMonths;

        private LoanDecisionAsterResult(
            Boolean approved,
            String reason,
            Integer approvedAmount,
            Integer interestRateBps,
            Integer termMonths
        ) {
            this.approved = approved;
            this.reason = reason;
            this.approvedAmount = approvedAmount;
            this.interestRateBps = interestRateBps;
            this.termMonths = termMonths;
        }
    }
}
