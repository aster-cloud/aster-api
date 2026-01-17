package io.aster.policy.graphql.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.policy.graphql.types.LifeInsuranceTypes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LifeInsuranceConverterTest {

    @Inject
    LifeInsuranceConverter converter;

    @Test
    void testGetDomain() {
        assertThat(converter.getDomain()).isEqualTo("life-insurance");
    }

    @Test
    void testToApplicantContext_normalInput() {
        LifeInsuranceTypes.Applicant applicant = new LifeInsuranceTypes.Applicant(
            "LI-100",
            40,
            "M",
            false,
            24,
            "OFFICE",
            88
        );

        Map<String, Object> applicantContext = converter.toApplicantContext(applicant);

        assertThat(applicantContext)
            .containsEntry("applicantId", "LI-100")
            .containsEntry("age", 40)
            .containsEntry("healthScore", 88)
            .containsEntry("smoker", false);
    }

    @Test
    void testToAsterContext_normalInput() {
        LifeInsuranceTypes.Applicant applicant = new LifeInsuranceTypes.Applicant(
            "LI-101",
            45,
            "F",
            true,
            29,
            "MODERATE",
            72
        );
        LifeInsuranceTypes.PolicyRequest request = new LifeInsuranceTypes.PolicyRequest(
            1_000_000,
            30,
            "TERM"
        );

        Map<String, Object> context = converter.toAsterContext(
            new LifeInsuranceConverter.LifeQuoteInput(applicant, request),
            "tenant-life"
        );

        assertThat(context).containsOnlyKeys("applicant", "request");
        assertThat(mapOf(context.get("request")))
            .containsEntry("coverageAmount", 1_000_000)
            .containsEntry("termYears", 30)
            .containsEntry("policyType", "TERM");
    }

    @Test
    void testToGraphQLResponse_normalOutput() {
        LifeQuoteResult result = new LifeQuoteResult(
            false,
            "HIGH_RISK",
            950,
            500_000,
            20
        );

        LifeInsuranceTypes.Quote quote = converter.toGraphQLResponse(result);

        assertThat(quote.approved).isFalse();
        assertThat(quote.reason).isEqualTo("HIGH_RISK");
        assertThat(quote.monthlyPremium).isEqualTo(950);
        assertThat(quote.coverageAmount).isEqualTo(500_000);
        assertThat(quote.termYears).isEqualTo(20);
    }

    @Test
    void testToApplicantContext_nullInput() {
        assertThatThrownBy(() -> converter.toApplicantContext(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testToGraphQLResponse_nullResult() {
        assertThat(converter.toGraphQLResponse(null)).isNull();
    }

    @Test
    void testToGraphQLResponse_invalidPayload() {
        assertThatThrownBy(() -> converter.toGraphQLResponse("invalid"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to convert");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class LifeQuoteResult {
        public final Boolean approved;
        public final String reason;
        public final Integer monthlyPremium;
        public final Integer coverageAmount;
        public final Integer termYears;

        private LifeQuoteResult(
            Boolean approved,
            String reason,
            Integer monthlyPremium,
            Integer coverageAmount,
            Integer termYears
        ) {
            this.approved = approved;
            this.reason = reason;
            this.monthlyPremium = monthlyPremium;
            this.coverageAmount = coverageAmount;
            this.termYears = termYears;
        }
    }
}

