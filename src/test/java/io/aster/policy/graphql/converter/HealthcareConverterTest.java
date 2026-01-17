package io.aster.policy.graphql.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.policy.graphql.types.HealthcareTypes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthcareConverterTest {

    @Inject
    HealthcareConverter converter;

    @Test
    void testGetDomain() {
        assertThat(converter.getDomain()).isEqualTo("healthcare");
    }

    @Test
    void testToAsterContext_eligibilityInput() {
        HealthcareTypes.Patient patient = new HealthcareTypes.Patient(
            "PAT-1",
            52,
            "PREMIUM",
            true,
            1,
            2_500
        );
        HealthcareTypes.Service service = new HealthcareTypes.Service(
            "SRV-100",
            "Knee Surgery",
            45_000,
            true
        );

        Map<String, Object> context = converter.toAsterContext(
            new HealthcareConverter.EligibilityInput(patient, service),
            "tenant-health"
        );

        assertThat(context).containsOnlyKeys("patient", "service");
        Map<String, Object> patientContext = mapOf(context.get("patient"));
        assertThat(patientContext)
            .containsEntry("patientId", "PAT-1")
            .doesNotContainKey("accountBalance");
        assertThat(mapOf(context.get("service")))
            .containsEntry("serviceCode", "SRV-100")
            .containsEntry("requiresPreAuth", true);
    }

    @Test
    void testToAsterContext_claimInput() {
        HealthcareTypes.Claim claim = new HealthcareTypes.Claim(
            "CLM-200",
            15_000,
            "2025-01-10",
            "ORTHO",
            "DX100",
            true,
            "PAT-1",
            "PROV-3"
        );
        HealthcareTypes.Provider provider = new HealthcareTypes.Provider(
            "PROV-3",
            true,
            92,
            "ORTHO"
        );

        Map<String, Object> context = converter.toAsterContext(
            new HealthcareConverter.ClaimInput(claim, provider, 80),
            "tenant-health"
        );

        assertThat(context).containsOnlyKeys("claim", "provider", "patientCoverage");
        assertThat(context).containsEntry("patientCoverage", 80);
        assertThat(mapOf(context.get("claim")))
            .containsEntry("claimId", "CLM-200")
            .containsEntry("diagnosisCode", "DX100");
    }

    @Test
    void testToGraphQLResponse_eligibilityResult() {
        EligibilityResult result = new EligibilityResult(
            true,
            "IN_NETWORK",
            90,
            4_500,
            true
        );

        Object converted = converter.toGraphQLResponse(result);
        assertThat(converted).isInstanceOf(HealthcareTypes.EligibilityCheck.class);
        HealthcareTypes.EligibilityCheck check = (HealthcareTypes.EligibilityCheck) converted;
        assertThat(check.eligible).isTrue();
        assertThat(check.coveragePercent).isEqualTo(90);
        assertThat(check.estimatedCost).isEqualTo(4_500);
    }

    @Test
    void testToGraphQLResponse_claimDecision() {
        ClaimDecisionResult result = new ClaimDecisionResult(
            false,
            "DOC_MISSING",
            0,
            true,
            "D05"
        );

        Object converted = converter.toGraphQLResponse(result);
        assertThat(converted).isInstanceOf(HealthcareTypes.ClaimDecision.class);
        HealthcareTypes.ClaimDecision decision = (HealthcareTypes.ClaimDecision) converted;
        assertThat(decision.approved).isFalse();
        assertThat(decision.requiresReview).isTrue();
        assertThat(decision.denialCode).isEqualTo("D05");
    }

    @Test
    void testToGraphQLResponse_mapWithTypeIndicator_claim() {
        Map<String, Object> result = Map.of(
            "_type", "claim",
            "approved", true,
            "reason", "OK",
            "approvedAmount", 12_000,
            "requiresReview", false,
            "denialCode", "NONE"
        );

        Object converted = converter.toGraphQLResponse(result);
        assertThat(converted).isInstanceOf(HealthcareTypes.ClaimDecision.class);
        HealthcareTypes.ClaimDecision decision = (HealthcareTypes.ClaimDecision) converted;
        assertThat(decision.approved).isTrue();
        assertThat(decision.denialCode).isEqualTo("NONE");
    }

    @Test
    void testToGraphQLResponse_mapWithTypeIndicator_eligibility() {
        Map<String, Object> result = Map.of(
            "_type", "eligibility",
            "eligible", false,
            "reason", "OUT_OF_NETWORK",
            "coveragePercent", 0,
            "estimatedCost", 18_000,
            "requiresPreAuth", true
        );

        Object converted = converter.toGraphQLResponse(result);
        assertThat(converted).isInstanceOf(HealthcareTypes.EligibilityCheck.class);
        HealthcareTypes.EligibilityCheck check = (HealthcareTypes.EligibilityCheck) converted;
        assertThat(check.eligible).isFalse();
        assertThat(check.reason).isEqualTo("OUT_OF_NETWORK");
        assertThat(check.estimatedCost).isEqualTo(18_000);
    }

    @Test
    void testToGraphQLResponse_mapFallbackByFields() {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("approved", true);
        result.put("reason", "FALLBACK");
        result.put("approvedAmount", 9_000);
        result.put("requiresReview", false);
        result.put("denialCode", null);

        Object converted = converter.toGraphQLResponse(result);
        assertThat(converted).isInstanceOf(HealthcareTypes.ClaimDecision.class);
    }

    @Test
    void testToGraphQLResponse_typeIndicatorOverridesFields() {
        Map<String, Object> result = Map.of(
            "_type", "eligibility",
            "approved", true,
            "reason", "SHOULD_IGNORE_APPROVED",
            "eligible", true,
            "coveragePercent", 75,
            "estimatedCost", 6_000,
            "requiresPreAuth", false
        );

        Object converted = converter.toGraphQLResponse(result);
        assertThat(converted).isInstanceOf(HealthcareTypes.EligibilityCheck.class);
        HealthcareTypes.EligibilityCheck check = (HealthcareTypes.EligibilityCheck) converted;
        assertThat(check.eligible).isTrue();
        assertThat(check.coveragePercent).isEqualTo(75);
    }

    @Test
    void testToAsterContext_nullInput() {
        assertThatThrownBy(() -> converter.toAsterContext(null, "tenant"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported healthcare input type");
    }

    @Test
    void testToGraphQLResponse_nullResult() {
        assertThat(converter.toGraphQLResponse(null)).isNull();
    }

    @Test
    void testToGraphQLResponse_invalidPayload() {
        assertThatThrownBy(() -> converter.toGraphQLResponse(new Object()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unsupported healthcare result type");
    }

    @Test
    void testToGraphQLResponse_invalidMap() {
        Map<String, Object> result = Map.of("unexpected", "value");
        assertThatThrownBy(() -> converter.toGraphQLResponse(result))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unsupported healthcare result map");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class EligibilityResult {
        public final Boolean eligible;
        public final String reason;
        public final Integer coveragePercent;
        public final Integer estimatedCost;
        public final Boolean requiresPreAuth;

        private EligibilityResult(
            Boolean eligible,
            String reason,
            Integer coveragePercent,
            Integer estimatedCost,
            Boolean requiresPreAuth
        ) {
            this.eligible = eligible;
            this.reason = reason;
            this.coveragePercent = coveragePercent;
            this.estimatedCost = estimatedCost;
            this.requiresPreAuth = requiresPreAuth;
        }
    }

    private static final class ClaimDecisionResult {
        public final Boolean approved;
        public final String reason;
        public final Integer approvedAmount;
        public final Boolean requiresReview;
        public final String denialCode;

        private ClaimDecisionResult(
            Boolean approved,
            String reason,
            Integer approvedAmount,
            Boolean requiresReview,
            String denialCode
        ) {
            this.approved = approved;
            this.reason = reason;
            this.approvedAmount = approvedAmount;
            this.requiresReview = requiresReview;
            this.denialCode = denialCode;
        }
    }
}
