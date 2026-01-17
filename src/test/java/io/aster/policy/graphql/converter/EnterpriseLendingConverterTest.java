package io.aster.policy.graphql.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.policy.graphql.types.EnterpriseLendingTypes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EnterpriseLendingConverterTest {

    @Inject
    EnterpriseLendingConverter converter;

    @Test
    void testGetDomain() {
        assertThat(converter.getDomain()).isEqualTo("enterprise-lending");
    }

    @Test
    void testToAsterContext_normalInput() {
        EnterpriseLendingTypes.EnterpriseInfo enterprise = new EnterpriseLendingTypes.EnterpriseInfo(
            "ENT-01",
            "Acme Corp",
            "Manufacturing",
            12,
            320,
            45_000_000,
            12,
            18
        );
        EnterpriseLendingTypes.FinancialPosition position = new EnterpriseLendingTypes.FinancialPosition(
            60_000_000,
            18_000_000,
            25_000_000,
            12_000_000,
            35_000_000,
            4_500_000,
            5_000_000
        );
        EnterpriseLendingTypes.BusinessHistory history = new EnterpriseLendingTypes.BusinessHistory(
            3,
            0,
            2,
            35,
            15_000_000,
            10
        );
        EnterpriseLendingTypes.LoanApplication application = new EnterpriseLendingTypes.LoanApplication(
            20_000_000,
            "FACTORY_EXPANSION",
            96,
            18_000_000,
            2
        );

        Map<String, Object> context = converter.toAsterContext(
            new EnterpriseLendingConverter.EnterpriseInput(enterprise, position, history, application),
            "tenant-enterprise"
        );

        assertThat(context).containsOnlyKeys("enterprise", "position", "history", "application");
        assertThat(mapOf(context.get("enterprise")))
            .containsEntry("companyId", "ENT-01")
            .containsEntry("industry", "Manufacturing")
            .containsEntry("profitMargin", 18);
        assertThat(mapOf(context.get("position")))
            .containsEntry("totalAssets", 60_000_000)
            .containsEntry("cashFlow", 4_500_000);
        assertThat(mapOf(context.get("history")))
            .containsEntry("defaultCount", 0)
            .containsEntry("creditUtilization", 35);
        assertThat(mapOf(context.get("application")))
            .containsEntry("requestedAmount", 20_000_000)
            .containsEntry("loanPurpose", "FACTORY_EXPANSION");
    }

    @Test
    void testToGraphQLResponse_normalOutput() {
        EnterpriseDecisionResult result = new EnterpriseDecisionResult(
            true,
            18_000_000,
            295,
            84,
            12_000_000,
            "Provide quarterly financial statements",
            "MEDIUM",
            760,
            "E01",
            "Strong revenue growth supports approval"
        );

        EnterpriseLendingTypes.LendingDecision decision = converter.toGraphQLResponse(result);

        assertThat(decision.approved).isTrue();
        assertThat(decision.approvedAmount).isEqualTo(18_000_000);
        assertThat(decision.interestRateBps).isEqualTo(295);
        assertThat(decision.collateralRequired).isEqualTo(12_000_000);
        assertThat(decision.detailedAnalysis).contains("revenue growth");
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

    private static final class EnterpriseDecisionResult {
        public final Boolean approved;
        public final Integer approvedAmount;
        public final Integer interestRateBps;
        public final Integer termMonths;
        public final Integer collateralRequired;
        public final String specialConditions;
        public final String riskCategory;
        public final Integer confidenceScore;
        public final String reasonCode;
        public final String detailedAnalysis;

        private EnterpriseDecisionResult(
            Boolean approved,
            Integer approvedAmount,
            Integer interestRateBps,
            Integer termMonths,
            Integer collateralRequired,
            String specialConditions,
            String riskCategory,
            Integer confidenceScore,
            String reasonCode,
            String detailedAnalysis
        ) {
            this.approved = approved;
            this.approvedAmount = approvedAmount;
            this.interestRateBps = interestRateBps;
            this.termMonths = termMonths;
            this.collateralRequired = collateralRequired;
            this.specialConditions = specialConditions;
            this.riskCategory = riskCategory;
            this.confidenceScore = confidenceScore;
            this.reasonCode = reasonCode;
            this.detailedAnalysis = detailedAnalysis;
        }
    }
}

