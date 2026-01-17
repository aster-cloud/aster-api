package io.aster.policy.graphql.converter;

import io.aster.policy.graphql.types.LifeInsuranceTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static io.aster.policy.graphql.converter.MapConversionUtils.getFieldValue;

/**
 * 人寿保险领域转换器，将GraphQL模型与策略上下文进行转换。
 */
@ApplicationScoped
public class LifeInsuranceConverter implements PolicyGraphQLConverter<LifeInsuranceConverter.LifeQuoteInput, LifeInsuranceTypes.Quote> {

    /**
     * 报价评估输入组合。
     */
    public record LifeQuoteInput(
        LifeInsuranceTypes.Applicant applicant,
        LifeInsuranceTypes.PolicyRequest request
    ) {
    }

    @Override
    public Map<String, Object> toAsterContext(LifeQuoteInput gqlInput, String tenantId) {
        return Map.of(
            "applicant", convertApplicant(gqlInput.applicant),
            "request", convertPolicyRequest(gqlInput.request)
        );
    }

    @Override
    public LifeInsuranceTypes.Quote toGraphQLResponse(Object asterResult) {
        if (asterResult == null) {
            return null;
        }

        try {
            Boolean approved = getFieldValue(asterResult, "approved", Boolean.class);
            String reason = getFieldValue(asterResult, "reason", String.class);
            Integer monthlyPremium = getFieldValue(asterResult, "monthlyPremium", Integer.class);
            Integer coverageAmount = getFieldValue(asterResult, "coverageAmount", Integer.class);
            Integer termYears = getFieldValue(asterResult, "termYears", Integer.class);

            // 验证至少有一个核心字段存在
            if (approved == null && reason == null && monthlyPremium == null) {
                throw new RuntimeException("Failed to convert life insurance quote: missing all core fields");
            }

            return new LifeInsuranceTypes.Quote(approved, reason, monthlyPremium, coverageAmount, termYears);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Failed to convert")) {
                throw e;
            }
            throw new RuntimeException("Failed to convert life insurance quote from Aster result", e);
        }
    }

    @Override
    public String getDomain() {
        return "life-insurance";
    }

    /**
     * 仅基于申请人构建上下文（用于风险评分等场景）。
     */
    public Map<String, Object> toApplicantContext(LifeInsuranceTypes.Applicant applicant) {
        return convertApplicant(applicant);
    }

    private Map<String, Object> convertApplicant(LifeInsuranceTypes.Applicant applicant) {
        return Map.of(
            "applicantId", applicant.applicantId,
            "age", applicant.age,
            "gender", applicant.gender,
            "smoker", applicant.smoker,
            "bmi", applicant.bmi,
            "occupation", applicant.occupation,
            "healthScore", applicant.healthScore
        );
    }

    private Map<String, Object> convertPolicyRequest(LifeInsuranceTypes.PolicyRequest request) {
        return Map.of(
            "coverageAmount", request.coverageAmount,
            "termYears", request.termYears,
            "policyType", request.policyType
        );
    }
}

