package io.aster.policy.graphql.converter;

import io.aster.policy.graphql.types.CreditCardTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static io.aster.policy.graphql.converter.MapConversionUtils.getFieldValue;

/**
 * 信用卡领域转换器，负责申请人、历史记录与产品信息的转换。
 */
@ApplicationScoped
public class CreditCardConverter implements PolicyGraphQLConverter<CreditCardConverter.CreditCardInput, CreditCardTypes.ApprovalDecision> {

    /**
     * 信用卡评估输入组合。
     */
    public record CreditCardInput(
        CreditCardTypes.ApplicantInfo applicant,
        CreditCardTypes.FinancialHistory history,
        CreditCardTypes.CreditCardOffer offer
    ) {
    }

    @Override
    public Map<String, Object> toAsterContext(CreditCardInput gqlInput, String tenantId) {
        return Map.of(
            "applicant", convertApplicant(gqlInput.applicant),
            "history", convertHistory(gqlInput.history),
            "offer", convertOffer(gqlInput.offer)
        );
    }

    @Override
    public CreditCardTypes.ApprovalDecision toGraphQLResponse(Object asterResult) {
        if (asterResult == null) {
            return null;
        }

        try {
            Boolean approved = getFieldValue(asterResult, "approved", Boolean.class);
            String reason = getFieldValue(asterResult, "reason", String.class);
            Integer approvedLimit = getFieldValue(asterResult, "approvedLimit", Integer.class);
            Integer interestRateAPR = getFieldValue(asterResult, "interestRateAPR", Integer.class);
            Integer monthlyFee = getFieldValue(asterResult, "monthlyFee", Integer.class);
            Integer creditLine = getFieldValue(asterResult, "creditLine", Integer.class);
            Boolean requiresDeposit = getFieldValue(asterResult, "requiresDeposit", Boolean.class);
            Integer depositAmount = getFieldValue(asterResult, "depositAmount", Integer.class);

            // 验证至少有一个核心字段存在
            if (approved == null && reason == null && approvedLimit == null) {
                throw new RuntimeException("Failed to convert credit card decision: missing all core fields");
            }

            return new CreditCardTypes.ApprovalDecision(
                approved,
                reason,
                approvedLimit,
                interestRateAPR,
                monthlyFee,
                creditLine,
                requiresDeposit,
                depositAmount
            );
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Failed to convert")) {
                throw e;
            }
            throw new RuntimeException("Failed to convert credit card decision from Aster result", e);
        }
    }

    @Override
    public String getDomain() {
        return "credit-card";
    }

    private Map<String, Object> convertApplicant(CreditCardTypes.ApplicantInfo applicant) {
        return Map.of(
            "applicantId", applicant.applicantId,
            "age", applicant.age,
            "annualIncome", applicant.annualIncome,
            "creditScore", applicant.creditScore,
            "existingCreditCards", applicant.existingCreditCards,
            "monthlyRent", applicant.monthlyRent,
            "employmentStatus", applicant.employmentStatus,
            "yearsAtCurrentJob", applicant.yearsAtCurrentJob
        );
    }

    private Map<String, Object> convertHistory(CreditCardTypes.FinancialHistory history) {
        return Map.of(
            "bankruptcyCount", history.bankruptcyCount,
            "latePayments", history.latePayments,
            "utilization", history.utilization,
            "accountAge", history.accountAge,
            "hardInquiries", history.hardInquiries
        );
    }

    private Map<String, Object> convertOffer(CreditCardTypes.CreditCardOffer offer) {
        return Map.of(
            "productType", offer.productType,
            "requestedLimit", offer.requestedLimit,
            "hasRewards", offer.hasRewards,
            "annualFee", offer.annualFee
        );
    }
}

