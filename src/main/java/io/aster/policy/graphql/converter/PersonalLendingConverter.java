package io.aster.policy.graphql.converter;

import io.aster.policy.graphql.types.PersonalLendingTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static io.aster.policy.graphql.converter.MapConversionUtils.getFieldValue;

/**
 * 个人贷款领域转换器，覆盖个人信息、收入、信用、债务与申请数据。
 */
@ApplicationScoped
public class PersonalLendingConverter implements PolicyGraphQLConverter<PersonalLendingConverter.PersonalLoanInput, PersonalLendingTypes.LoanDecision> {

    /**
     * 个人贷款评估输入组合。
     */
    public record PersonalLoanInput(
        PersonalLendingTypes.PersonalInfo personal,
        PersonalLendingTypes.IncomeProfile income,
        PersonalLendingTypes.CreditProfile credit,
        PersonalLendingTypes.DebtProfile debt,
        PersonalLendingTypes.LoanRequest request
    ) {
    }

    @Override
    public Map<String, Object> toAsterContext(PersonalLoanInput gqlInput, String tenantId) {
        return Map.of(
            "personal", convertPersonalInfo(gqlInput.personal),
            "income", convertIncomeProfile(gqlInput.income),
            "credit", convertCreditProfile(gqlInput.credit),
            "debt", convertDebtProfile(gqlInput.debt),
            "request", convertLoanRequest(gqlInput.request)
        );
    }

    @Override
    public PersonalLendingTypes.LoanDecision toGraphQLResponse(Object asterResult) {
        if (asterResult == null) {
            return null;
        }

        try {
            Boolean approved = getFieldValue(asterResult, "approved", Boolean.class);
            Integer approvedAmount = getFieldValue(asterResult, "approvedAmount", Integer.class);
            Integer interestRateBps = getFieldValue(asterResult, "interestRateBps", Integer.class);
            Integer termMonths = getFieldValue(asterResult, "termMonths", Integer.class);
            Integer monthlyPayment = getFieldValue(asterResult, "monthlyPayment", Integer.class);
            Integer downPaymentRequired = getFieldValue(asterResult, "downPaymentRequired", Integer.class);
            String conditions = getFieldValue(asterResult, "conditions", String.class);
            String riskLevel = getFieldValue(asterResult, "riskLevel", String.class);
            Integer decisionScore = getFieldValue(asterResult, "decisionScore", Integer.class);
            String reasonCode = getFieldValue(asterResult, "reasonCode", String.class);
            String recommendations = getFieldValue(asterResult, "recommendations", String.class);

            // 验证至少有一个核心字段存在
            if (approved == null && approvedAmount == null && reasonCode == null) {
                throw new RuntimeException("Failed to convert personal lending decision: missing all core fields");
            }

            return new PersonalLendingTypes.LoanDecision(
                approved,
                approvedAmount,
                interestRateBps,
                termMonths,
                monthlyPayment,
                downPaymentRequired,
                conditions,
                riskLevel,
                decisionScore,
                reasonCode,
                recommendations
            );
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Failed to convert")) {
                throw e;
            }
            throw new RuntimeException("Failed to convert personal lending decision from Aster result", e);
        }
    }

    @Override
    public String getDomain() {
        return "personal-lending";
    }

    private Map<String, Object> convertPersonalInfo(PersonalLendingTypes.PersonalInfo personal) {
        return Map.of(
            "applicantId", personal.applicantId,
            "age", personal.age,
            "educationLevel", personal.educationLevel,
            "employmentStatus", personal.employmentStatus,
            "occupation", personal.occupation,
            "yearsAtJob", personal.yearsAtJob,
            "monthsAtAddress", personal.monthsAtAddress,
            "maritalStatus", personal.maritalStatus,
            "dependents", personal.dependents
        );
    }

    private Map<String, Object> convertIncomeProfile(PersonalLendingTypes.IncomeProfile income) {
        return Map.of(
            "monthlyIncome", income.monthlyIncome,
            "additionalIncome", income.additionalIncome,
            "spouseIncome", income.spouseIncome,
            "rentIncome", income.rentIncome,
            "incomeStability", income.incomeStability,
            "incomeGrowthRate", income.incomeGrowthRate
        );
    }

    private Map<String, Object> convertCreditProfile(PersonalLendingTypes.CreditProfile credit) {
        return Map.of(
            "creditScore", credit.creditScore,
            "creditHistory", credit.creditHistory,
            "activeLoans", credit.activeLoans,
            "creditCardCount", credit.creditCardCount,
            "creditUtilization", credit.creditUtilization,
            "latePayments", credit.latePayments,
            "defaults", credit.defaults,
            "bankruptcies", credit.bankruptcies,
            "inquiries", credit.inquiries
        );
    }

    private Map<String, Object> convertDebtProfile(PersonalLendingTypes.DebtProfile debt) {
        return Map.of(
            "totalMonthlyDebt", debt.totalMonthlyDebt,
            "mortgagePayment", debt.mortgagePayment,
            "carPayment", debt.carPayment,
            "studentLoanPayment", debt.studentLoanPayment,
            "creditCardMinPayment", debt.creditCardMinPayment,
            "otherDebtPayment", debt.otherDebtPayment
        );
    }

    private Map<String, Object> convertLoanRequest(PersonalLendingTypes.LoanRequest request) {
        return Map.of(
            "requestedAmount", request.requestedAmount,
            "purpose", request.purpose,
            "termMonths", request.termMonths,
            "downPayment", request.downPayment,
            "collateralValue", request.collateralValue
        );
    }
}
