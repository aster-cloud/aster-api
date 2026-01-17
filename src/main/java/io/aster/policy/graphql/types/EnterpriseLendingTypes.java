package io.aster.policy.graphql.types;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * 企业贷款GraphQL类型定义 / Enterprise Lending GraphQL Type Definitions
 */
public class EnterpriseLendingTypes {

    @Type("EnterpriseInfo")
    @Description("企业基本信息 / Enterprise basic information")
    public static class EnterpriseInfo {
        @NonNull
        @Description("企业ID / Company ID")
        public String companyId;

        @NonNull
        @Description("企业名称 / Company name")
        public String companyName;

        @NonNull
        @Description("所属行业 / Industry")
        public String industry;

        @NonNull
        @Description("经营年限 / Years in business")
        public Integer yearsInBusiness;

        @NonNull
        @Description("员工人数 / Employee count")
        public Integer employeeCount;

        @NonNull
        @Description("年营业额 / Annual revenue")
        public Integer annualRevenue;

        @NonNull
        @Description("营收增长率(%) / Revenue growth rate")
        public Integer revenueGrowthRate;

        @NonNull
        @Description("利润率(%) / Profit margin")
        public Integer profitMargin;

        public EnterpriseInfo() {}

        public EnterpriseInfo(String companyId, String companyName, String industry, Integer yearsInBusiness,
                             Integer employeeCount, Integer annualRevenue, Integer revenueGrowthRate, Integer profitMargin) {
            this.companyId = companyId;
            this.companyName = companyName;
            this.industry = industry;
            this.yearsInBusiness = yearsInBusiness;
            this.employeeCount = employeeCount;
            this.annualRevenue = annualRevenue;
            this.revenueGrowthRate = revenueGrowthRate;
            this.profitMargin = profitMargin;
        }
    }

    @Type("FinancialPosition")
    @Description("财务状况 / Financial position")
    public static class FinancialPosition {
        @NonNull
        @Description("总资产 / Total assets")
        public Integer totalAssets;

        @NonNull
        @Description("流动资产 / Current assets")
        public Integer currentAssets;

        @NonNull
        @Description("总负债 / Total liabilities")
        public Integer totalLiabilities;

        @NonNull
        @Description("流动负债 / Current liabilities")
        public Integer currentLiabilities;

        @NonNull
        @Description("净资产 / Equity")
        public Integer equity;

        @NonNull
        @Description("现金流 / Cash flow")
        public Integer cashFlow;

        @NonNull
        @Description("未偿债务 / Outstanding debt")
        public Integer outstandingDebt;

        public FinancialPosition() {}

        public FinancialPosition(Integer totalAssets, Integer currentAssets, Integer totalLiabilities,
                                Integer currentLiabilities, Integer equity, Integer cashFlow, Integer outstandingDebt) {
            this.totalAssets = totalAssets;
            this.currentAssets = currentAssets;
            this.totalLiabilities = totalLiabilities;
            this.currentLiabilities = currentLiabilities;
            this.equity = equity;
            this.cashFlow = cashFlow;
            this.outstandingDebt = outstandingDebt;
        }
    }

    @Type("BusinessHistory")
    @Description("企业历史记录 / Business history")
    public static class BusinessHistory {
        @NonNull
        @Description("历史贷款次数 / Previous loans")
        public Integer previousLoans;

        @NonNull
        @Description("违约次数 / Default count")
        public Integer defaultCount;

        @NonNull
        @Description("逾期次数 / Late payments")
        public Integer latePayments;

        @NonNull
        @Description("信用使用率(%) / Credit utilization")
        public Integer creditUtilization;

        @NonNull
        @Description("最大贷款金额 / Largest loan amount")
        public Integer largestLoanAmount;

        @NonNull
        @Description("合作年限 / Relationship years")
        public Integer relationshipYears;

        public BusinessHistory() {}

        public BusinessHistory(Integer previousLoans, Integer defaultCount, Integer latePayments,
                              Integer creditUtilization, Integer largestLoanAmount, Integer relationshipYears) {
            this.previousLoans = previousLoans;
            this.defaultCount = defaultCount;
            this.latePayments = latePayments;
            this.creditUtilization = creditUtilization;
            this.largestLoanAmount = largestLoanAmount;
            this.relationshipYears = relationshipYears;
        }
    }

    @Type("EnterpriseLoanApplication")
    @Description("企业贷款申请 / Enterprise loan application")
    public static class LoanApplication {
        @NonNull
        @Description("申请金额 / Requested amount")
        public Integer requestedAmount;

        @NonNull
        @Description("贷款用途 / Loan purpose")
        public String loanPurpose;

        @NonNull
        @Description("贷款期限(月) / Term in months")
        public Integer termMonths;

        @NonNull
        @Description("抵押物价值 / Collateral value")
        public Integer collateralValue;

        @NonNull
        @Description("担保人数量 / Guarantor count")
        public Integer guarantorCount;

        public LoanApplication() {}

        public LoanApplication(Integer requestedAmount, String loanPurpose, Integer termMonths,
                              Integer collateralValue, Integer guarantorCount) {
            this.requestedAmount = requestedAmount;
            this.loanPurpose = loanPurpose;
            this.termMonths = termMonths;
            this.collateralValue = collateralValue;
            this.guarantorCount = guarantorCount;
        }
    }

    @Type("EnterpriseLendingDecision")
    @Description("企业贷款决策 / Enterprise lending decision")
    public static class LendingDecision {
        @NonNull
        @Description("是否批准 / Approved")
        public Boolean approved;

        @NonNull
        @Description("批准金额 / Approved amount")
        public Integer approvedAmount;

        @NonNull
        @Description("利率(基点) / Interest rate in basis points")
        public Integer interestRateBps;

        @NonNull
        @Description("贷款期限(月) / Term in months")
        public Integer termMonths;

        @NonNull
        @Description("所需抵押物 / Collateral required")
        public Integer collateralRequired;

        @NonNull
        @Description("特殊条件 / Special conditions")
        public String specialConditions;

        @NonNull
        @Description("风险类别 / Risk category")
        public String riskCategory;

        @NonNull
        @Description("信心评分 / Confidence score")
        public Integer confidenceScore;

        @NonNull
        @Description("原因代码 / Reason code")
        public String reasonCode;

        @NonNull
        @Description("详细分析 / Detailed analysis")
        public String detailedAnalysis;

        public LendingDecision() {}

        public LendingDecision(Boolean approved, Integer approvedAmount, Integer interestRateBps, Integer termMonths,
                              Integer collateralRequired, String specialConditions, String riskCategory,
                              Integer confidenceScore, String reasonCode, String detailedAnalysis) {
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
