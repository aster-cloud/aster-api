package io.aster.policy.graphql.types;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * 个人贷款GraphQL类型定义 / Personal Lending GraphQL Type Definitions
 */
public class PersonalLendingTypes {

    @Type("PersonalInfo")
    @Description("个人基本信息 / Personal basic information")
    public static class PersonalInfo {
        @NonNull
        @Description("申请人ID / Applicant ID")
        public String applicantId;

        @NonNull
        @Description("年龄 / Age")
        public Integer age;

        @NonNull
        @Description("教育水平 / Education level")
        public String educationLevel;

        @NonNull
        @Description("就业状态 / Employment status")
        public String employmentStatus;

        @NonNull
        @Description("职业 / Occupation")
        public String occupation;

        @NonNull
        @Description("工作年限 / Years at job")
        public Integer yearsAtJob;

        @NonNull
        @Description("居住月数 / Months at address")
        public Integer monthsAtAddress;

        @NonNull
        @Description("婚姻状态 / Marital status")
        public String maritalStatus;

        @NonNull
        @Description("被赡养人数 / Dependents")
        public Integer dependents;

        public PersonalInfo() {}

        public PersonalInfo(String applicantId, Integer age, String educationLevel, String employmentStatus,
                           String occupation, Integer yearsAtJob, Integer monthsAtAddress,
                           String maritalStatus, Integer dependents) {
            this.applicantId = applicantId;
            this.age = age;
            this.educationLevel = educationLevel;
            this.employmentStatus = employmentStatus;
            this.occupation = occupation;
            this.yearsAtJob = yearsAtJob;
            this.monthsAtAddress = monthsAtAddress;
            this.maritalStatus = maritalStatus;
            this.dependents = dependents;
        }
    }

    @Type("IncomeProfile")
    @Description("收入状况 / Income profile")
    public static class IncomeProfile {
        @NonNull
        @Description("月收入 / Monthly income")
        public Integer monthlyIncome;

        @NonNull
        @Description("额外收入 / Additional income")
        public Integer additionalIncome;

        @NonNull
        @Description("配偶收入 / Spouse income")
        public Integer spouseIncome;

        @NonNull
        @Description("租金收入 / Rent income")
        public Integer rentIncome;

        @NonNull
        @Description("收入稳定性 / Income stability")
        public String incomeStability;

        @NonNull
        @Description("收入增长率(%) / Income growth rate")
        public Integer incomeGrowthRate;

        public IncomeProfile() {}

        public IncomeProfile(Integer monthlyIncome, Integer additionalIncome, Integer spouseIncome,
                            Integer rentIncome, String incomeStability, Integer incomeGrowthRate) {
            this.monthlyIncome = monthlyIncome;
            this.additionalIncome = additionalIncome;
            this.spouseIncome = spouseIncome;
            this.rentIncome = rentIncome;
            this.incomeStability = incomeStability;
            this.incomeGrowthRate = incomeGrowthRate;
        }
    }

    @Type("CreditProfile")
    @Description("信用状况 / Credit profile")
    public static class CreditProfile {
        @NonNull
        @Description("信用评分 / Credit score")
        public Integer creditScore;

        @NonNull
        @Description("信用历史(月) / Credit history in months")
        public Integer creditHistory;

        @NonNull
        @Description("活跃贷款数 / Active loans")
        public Integer activeLoans;

        @NonNull
        @Description("信用卡数量 / Credit card count")
        public Integer creditCardCount;

        @NonNull
        @Description("信用使用率(%) / Credit utilization")
        public Integer creditUtilization;

        @NonNull
        @Description("逾期次数 / Late payments")
        public Integer latePayments;

        @NonNull
        @Description("违约次数 / Defaults")
        public Integer defaults;

        @NonNull
        @Description("破产次数 / Bankruptcies")
        public Integer bankruptcies;

        @NonNull
        @Description("征信查询次数 / Inquiries")
        public Integer inquiries;

        public CreditProfile() {}

        public CreditProfile(Integer creditScore, Integer creditHistory, Integer activeLoans,
                            Integer creditCardCount, Integer creditUtilization, Integer latePayments,
                            Integer defaults, Integer bankruptcies, Integer inquiries) {
            this.creditScore = creditScore;
            this.creditHistory = creditHistory;
            this.activeLoans = activeLoans;
            this.creditCardCount = creditCardCount;
            this.creditUtilization = creditUtilization;
            this.latePayments = latePayments;
            this.defaults = defaults;
            this.bankruptcies = bankruptcies;
            this.inquiries = inquiries;
        }
    }

    @Type("DebtProfile")
    @Description("债务状况 / Debt profile")
    public static class DebtProfile {
        @NonNull
        @Description("总月债务 / Total monthly debt")
        public Integer totalMonthlyDebt;

        @NonNull
        @Description("房贷月供 / Mortgage payment")
        public Integer mortgagePayment;

        @NonNull
        @Description("车贷月供 / Car payment")
        public Integer carPayment;

        @NonNull
        @Description("学贷月供 / Student loan payment")
        public Integer studentLoanPayment;

        @NonNull
        @Description("信用卡最低还款 / Credit card minimum payment")
        public Integer creditCardMinPayment;

        @NonNull
        @Description("其他月度债务 / Other debt payment")
        public Integer otherDebtPayment;

        @NonNull
        @Description("总未偿债务 / Total outstanding debt")
        public Integer totalOutstandingDebt;

        public DebtProfile() {}

        public DebtProfile(Integer totalMonthlyDebt, Integer mortgagePayment, Integer carPayment,
                          Integer studentLoanPayment, Integer creditCardMinPayment, Integer otherDebtPayment,
                          Integer totalOutstandingDebt) {
            this.totalMonthlyDebt = totalMonthlyDebt;
            this.mortgagePayment = mortgagePayment;
            this.carPayment = carPayment;
            this.studentLoanPayment = studentLoanPayment;
            this.creditCardMinPayment = creditCardMinPayment;
            this.otherDebtPayment = otherDebtPayment;
            this.totalOutstandingDebt = totalOutstandingDebt;
        }
    }

    @Type("PersonalLoanRequest")
    @Description("个人贷款申请 / Personal loan request")
    public static class LoanRequest {
        @NonNull
        @Description("申请金额 / Requested amount")
        public Integer requestedAmount;

        @NonNull
        @Description("贷款用途 / Loan purpose")
        public String purpose;

        @NonNull
        @Description("期望期限(月) / Desired term in months")
        public Integer termMonths;

        @NonNull
        @Description("首付金额 / Down payment")
        public Integer downPayment;

        @NonNull
        @Description("抵押物价值 / Collateral value")
        public Integer collateralValue;

        public LoanRequest() {}

        public LoanRequest(Integer requestedAmount, String purpose, Integer termMonths,
                          Integer downPayment, Integer collateralValue) {
            this.requestedAmount = requestedAmount;
            this.purpose = purpose;
            this.termMonths = termMonths;
            this.downPayment = downPayment;
            this.collateralValue = collateralValue;
        }
    }

    @Type("PersonalLoanDecision")
    @Description("个人贷款决策 / Personal loan decision")
    public static class LoanDecision {
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
        @Description("月供 / Monthly payment")
        public Integer monthlyPayment;

        @NonNull
        @Description("所需首付 / Down payment required")
        public Integer downPaymentRequired;

        @NonNull
        @Description("附加条件 / Conditions")
        public String conditions;

        @NonNull
        @Description("风险等级 / Risk level")
        public String riskLevel;

        @NonNull
        @Description("决策评分 / Decision score")
        public Integer decisionScore;

        @NonNull
        @Description("原因代码 / Reason code")
        public String reasonCode;

        @NonNull
        @Description("建议 / Recommendations")
        public String recommendations;

        public LoanDecision() {}

        public LoanDecision(Boolean approved, Integer approvedAmount, Integer interestRateBps, Integer termMonths,
                           Integer monthlyPayment, Integer downPaymentRequired, String conditions,
                           String riskLevel, Integer decisionScore, String reasonCode, String recommendations) {
            this.approved = approved;
            this.approvedAmount = approvedAmount;
            this.interestRateBps = interestRateBps;
            this.termMonths = termMonths;
            this.monthlyPayment = monthlyPayment;
            this.downPaymentRequired = downPaymentRequired;
            this.conditions = conditions;
            this.riskLevel = riskLevel;
            this.decisionScore = decisionScore;
            this.reasonCode = reasonCode;
            this.recommendations = recommendations;
        }
    }
}
