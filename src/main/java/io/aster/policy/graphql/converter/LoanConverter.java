package io.aster.policy.graphql.converter;

import io.aster.policy.graphql.types.LoanTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static io.aster.policy.graphql.converter.MapConversionUtils.buildMap;
import static io.aster.policy.graphql.converter.MapConversionUtils.getFieldValue;

/**
 * 贷款领域 GraphQL 转换器，实现输入输出与策略上下文的映射。
 */
@ApplicationScoped
public class LoanConverter implements PolicyGraphQLConverter<LoanConverter.LoanInputWrapper, LoanTypes.Decision> {

    /**
     * 组合贷款输入，便于统一转换。
     */
    public record LoanInputWrapper(LoanTypes.Application application,
                                   LoanTypes.Applicant applicant) {
    }

    @Override
    public Map<String, Object> toAsterContext(LoanInputWrapper gqlInput, String tenantId) {
        return Map.of(
            "application", convertToAsterLoanApplication(gqlInput.application),
            "applicant", convertToAsterLoanApplicant(gqlInput.applicant)
        );
    }

    @Override
    public LoanTypes.Decision toGraphQLResponse(Object asterResult) {
        if (asterResult == null) {
            return null;
        }

        try {
            Boolean approved = getFieldValue(asterResult, "approved", Boolean.class);
            String reason = getFieldValue(asterResult, "reason", String.class);
            Integer approvedAmount = getFieldValue(asterResult, "approvedAmount", Integer.class);
            Integer interestRateBps = getFieldValue(asterResult, "interestRateBps", Integer.class);
            Integer termMonths = getFieldValue(asterResult, "termMonths", Integer.class);

            // 验证至少有一个核心字段存在
            if (approved == null && reason == null && approvedAmount == null) {
                throw new RuntimeException("Failed to convert loan decision: missing all core fields (approved, reason, approvedAmount)");
            }

            return new LoanTypes.Decision(approved, reason, approvedAmount, interestRateBps, termMonths);
        } catch (RuntimeException e) {
            // 重新抛出已有的RuntimeException
            if (e.getMessage() != null && e.getMessage().contains("Failed to convert")) {
                throw e;
            }
            throw new RuntimeException("Failed to convert loan decision from Aster result", e);
        }
    }

    @Override
    public String getDomain() {
        return "loans";
    }

    private Map<String, Object> convertToAsterLoanApplication(LoanTypes.Application application) {
        // Aster LoanApplication 仅接受此处四个字段，原始 loanId 在策略执行中不使用
        return Map.of(
            "applicantId", application.applicantId,
            "amount", application.amountRequested,
            "termMonths", application.termMonths,
            "purpose", application.purposeCode
        );
    }

    private Map<String, Object> convertToAsterLoanApplicant(LoanTypes.Applicant applicant) {
        return Map.of(
            "age", applicant.age,
            "creditScore", applicant.creditScore,
            "annualIncome", applicant.annualIncome,
            "monthlyDebt", applicant.existingDebtMonthly,
            "yearsEmployed", applicant.yearsEmployed
        );
    }
}
