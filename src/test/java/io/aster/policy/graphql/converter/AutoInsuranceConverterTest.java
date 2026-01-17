package io.aster.policy.graphql.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.policy.graphql.types.AutoInsuranceTypes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AutoInsuranceConverterTest {

    @Inject
    AutoInsuranceConverter converter;

    @Test
    void testGetDomain() {
        assertThat(converter.getDomain()).isEqualTo("auto-insurance");
    }

    @Test
    void testToAsterContext_normalInput() {
        AutoInsuranceTypes.Driver driver = new AutoInsuranceTypes.Driver(
            "DRV-100",
            42,
            20,
            1,
            0,
            765
        );
        AutoInsuranceTypes.Vehicle vehicle = new AutoInsuranceTypes.Vehicle(
            "VIN1234567890",
            2024,
            "Tesla",
            "Model S",
            110_000,
            9
        );

        Map<String, Object> context = converter.toAsterContext(
            new AutoInsuranceConverter.AutoQuoteInput(driver, vehicle),
            "tenant-auto"
        );

        assertThat(context).containsOnlyKeys("driver", "vehicle");
        assertThat(mapOf(context.get("driver")))
            .containsEntry("driverId", "DRV-100")
            .containsEntry("accidentCount", 1)
            .containsEntry("violationCount", 0)
            .containsEntry("creditScore", 765);
        assertThat(mapOf(context.get("vehicle")))
            .containsEntry("vin", "VIN1234567890")
            .containsEntry("year", 2024)
            .containsEntry("make", "Tesla")
            .containsEntry("value", 110_000)
            .containsEntry("safetyRating", 9);
    }

    @Test
    void testToGraphQLResponse_normalOutput() {
        AutoQuoteResult result = new AutoQuoteResult(
            true,
            "ELIGIBLE",
            325,
            1_000,
            250_000
        );

        AutoInsuranceTypes.PolicyQuote quote = converter.toGraphQLResponse(result);

        assertThat(quote.approved).isTrue();
        assertThat(quote.reason).isEqualTo("ELIGIBLE");
        assertThat(quote.monthlyPremium).isEqualTo(325);
        assertThat(quote.deductible).isEqualTo(1_000);
        assertThat(quote.coverageLimit).isEqualTo(250_000);
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

    private static final class AutoQuoteResult {
        public final Boolean approved;
        public final String reason;
        public final Integer monthlyPremium;
        public final Integer deductible;
        public final Integer coverageLimit;

        private AutoQuoteResult(
            Boolean approved,
            String reason,
            Integer monthlyPremium,
            Integer deductible,
            Integer coverageLimit
        ) {
            this.approved = approved;
            this.reason = reason;
            this.monthlyPremium = monthlyPremium;
            this.deductible = deductible;
            this.coverageLimit = coverageLimit;
        }
    }
}

