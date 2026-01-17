package io.aster.policy.graphql.converter;

import io.aster.policy.graphql.types.AutoInsuranceTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static io.aster.policy.graphql.converter.MapConversionUtils.getFieldValue;

/**
 * 汽车保险领域转换器，将驾驶员与车辆信息映射为策略上下文，并解析策略结果。
 */
@ApplicationScoped
public class AutoInsuranceConverter implements PolicyGraphQLConverter<AutoInsuranceConverter.AutoQuoteInput, AutoInsuranceTypes.PolicyQuote> {

    /**
     * 汽车保险报价输入组合。
     */
    public record AutoQuoteInput(
        AutoInsuranceTypes.Driver driver,
        AutoInsuranceTypes.Vehicle vehicle
    ) {
    }

    @Override
    public Map<String, Object> toAsterContext(AutoQuoteInput gqlInput, String tenantId) {
        return Map.of(
            "driver", convertDriver(gqlInput.driver),
            "vehicle", convertVehicle(gqlInput.vehicle)
        );
    }

    @Override
    public AutoInsuranceTypes.PolicyQuote toGraphQLResponse(Object asterResult) {
        if (asterResult == null) {
            return null;
        }

        try {
            Boolean approved = getFieldValue(asterResult, "approved", Boolean.class);
            String reason = getFieldValue(asterResult, "reason", String.class);
            Integer monthlyPremium = getFieldValue(asterResult, "monthlyPremium", Integer.class);
            Integer deductible = getFieldValue(asterResult, "deductible", Integer.class);
            Integer coverageLimit = getFieldValue(asterResult, "coverageLimit", Integer.class);

            // 验证至少有一个核心字段存在
            if (approved == null && reason == null && monthlyPremium == null) {
                throw new RuntimeException("Failed to convert auto insurance quote: missing all core fields");
            }

            return new AutoInsuranceTypes.PolicyQuote(approved, reason, monthlyPremium, deductible, coverageLimit);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Failed to convert")) {
                throw e;
            }
            throw new RuntimeException("Failed to convert auto insurance quote from Aster result", e);
        }
    }

    @Override
    public String getDomain() {
        return "auto-insurance";
    }

    private Map<String, Object> convertDriver(AutoInsuranceTypes.Driver driver) {
        return Map.of(
            "driverId", driver.driverId,
            "age", driver.age,
            "yearsLicensed", driver.yearsLicensed,
            "accidentCount", driver.accidentCount,
            "violationCount", driver.violationCount,
            "creditScore", driver.creditScore
        );
    }

    private Map<String, Object> convertVehicle(AutoInsuranceTypes.Vehicle vehicle) {
        return Map.of(
            "vin", vehicle.vin,
            "year", vehicle.year,
            "make", vehicle.make,
            "model", vehicle.model,
            "value", vehicle.value,
            "safetyRating", vehicle.safetyRating
        );
    }
}

