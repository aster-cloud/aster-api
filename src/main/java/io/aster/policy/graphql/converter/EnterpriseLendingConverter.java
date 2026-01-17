package io.aster.policy.graphql.converter;

import io.aster.policy.graphql.types.EnterpriseLendingTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static io.aster.policy.graphql.converter.MapConversionUtils.getFieldValue;

/**
 * 企业贷款领域转换器，处理企业信息、财务状况与贷款申请的映射。
 */
@ApplicationScoped
public class EnterpriseLendingConverter implements PolicyGraphQLConverter<EnterpriseLendingConverter.EnterpriseInput, EnterpriseLendingTypes.LendingDecision> {

    /**
     * 企业贷款评估输入组合。
     */
    public record EnterpriseInput(
        EnterpriseLendingTypes.EnterpriseInfo enterprise,
        EnterpriseLendingTypes.FinancialPosition position,
        EnterpriseLendingTypes.BusinessHistory history,
        EnterpriseLendingTypes.LoanApplication application
    ) {
    }

    @Override
    public Map<String, Object> toAsterContext(EnterpriseInput gqlInput, String tenantId) {
        return Map.of(
            "enterprise", convertEnterpriseInfo(gqlInput.enterprise),
            "position", convertFinancialPosition(gqlInput.position),
            "history", convertBusinessHistory(gqlInput.history),
            "application", convertLoanApplication(gqlInput.application)
        );
    }

    @Override
    public EnterpriseLendingTypes.LendingDecision toGraphQLResponse(Object asterResult) {
        if (asterResult == null) {
            return null;
        }

        try {
            Boolean approved = getFieldValue(asterResult, "approved", Boolean.class);
            Integer approvedAmount = getFieldValue(asterResult, "approvedAmount", Integer.class);
            Integer interestRateBps = getFieldValue(asterResult, "interestRateBps", Integer.class);
            Integer termMonths = getFieldValue(asterResult, "termMonths", Integer.class);
            Integer collateralRequired = getFieldValue(asterResult, "collateralRequired", Integer.class);
            String specialConditions = getFieldValue(asterResult, "specialConditions", String.class);
            String riskCategory = getFieldValue(asterResult, "riskCategory", String.class);
            Integer confidenceScore = getFieldValue(asterResult, "confidenceScore", Integer.class);
            String reasonCode = getFieldValue(asterResult, "reasonCode", String.class);
            String detailedAnalysis = getFieldValue(asterResult, "detailedAnalysis", String.class);

            // 验证至少有一个核心字段存在
            if (approved == null && approvedAmount == null && reasonCode == null) {
                throw new RuntimeException("Failed to convert enterprise lending decision: missing all core fields");
            }

            return new EnterpriseLendingTypes.LendingDecision(
                approved,
                approvedAmount,
                interestRateBps,
                termMonths,
                collateralRequired,
                specialConditions,
                riskCategory,
                confidenceScore,
                reasonCode,
                detailedAnalysis
            );
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Failed to convert")) {
                throw e;
            }
            throw new RuntimeException("Failed to convert enterprise lending decision from Aster result", e);
        }
    }

    @Override
    public String getDomain() {
        return "enterprise-lending";
    }

    private Map<String, Object> convertEnterpriseInfo(EnterpriseLendingTypes.EnterpriseInfo enterprise) {
        return Map.of(
            "companyId", enterprise.companyId,
            "companyName", enterprise.companyName,
            "industry", enterprise.industry,
            "yearsInBusiness", enterprise.yearsInBusiness,
            "employeeCount", enterprise.employeeCount,
            "annualRevenue", enterprise.annualRevenue,
            "revenueGrowthRate", enterprise.revenueGrowthRate,
            "profitMargin", enterprise.profitMargin
        );
    }

    private Map<String, Object> convertFinancialPosition(EnterpriseLendingTypes.FinancialPosition position) {
        return Map.of(
            "totalAssets", position.totalAssets,
            "currentAssets", position.currentAssets,
            "totalLiabilities", position.totalLiabilities,
            "currentLiabilities", position.currentLiabilities,
            "equity", position.equity,
            "cashFlow", position.cashFlow,
            "outstandingDebt", position.outstandingDebt
        );
    }

    private Map<String, Object> convertBusinessHistory(EnterpriseLendingTypes.BusinessHistory history) {
        return Map.of(
            "previousLoans", history.previousLoans,
            "defaultCount", history.defaultCount,
            "latePayments", history.latePayments,
            "creditUtilization", history.creditUtilization,
            "largestLoanAmount", history.largestLoanAmount,
            "relationshipYears", history.relationshipYears
        );
    }

    private Map<String, Object> convertLoanApplication(EnterpriseLendingTypes.LoanApplication application) {
        return Map.of(
            "requestedAmount", application.requestedAmount,
            "loanPurpose", application.loanPurpose,
            "termMonths", application.termMonths,
            "collateralValue", application.collateralValue,
            "guarantorCount", application.guarantorCount
        );
    }
}
