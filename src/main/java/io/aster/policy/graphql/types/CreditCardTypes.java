package io.aster.policy.graphql.types;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * GraphQL类型定义 - 信用卡审批
 * GraphQL Type Definitions - Credit Card Approval
 */
public class CreditCardTypes {

    @Type("CreditCardApplicantInfo")
    @Name("CreditCardApplicant")
    @Description("申请人信息 / Applicant information")
    public static class ApplicantInfo {
        @NonNull
        @Description("申请人ID / Applicant ID")
        public String applicantId;

        @NonNull
        @Description("年龄 / Age")
        public Integer age;

        @NonNull
        @Description("年收入 / Annual income")
        public Integer annualIncome;

        @NonNull
        @Description("信用评分 / Credit score")
        public Integer creditScore;

        @NonNull
        @Description("现有信用卡数量 / Existing credit cards")
        public Integer existingCreditCards;

        @NonNull
        @Description("月租金 / Monthly rent")
        public Integer monthlyRent;

        @NonNull
        @Description("就业状态 / Employment status (Full-time/Part-time/Self-employed/Unemployed)")
        public String employmentStatus;

        @NonNull
        @Description("当前工作年限 / Years at current job")
        public Integer yearsAtCurrentJob;

        public ApplicantInfo() {}

        public ApplicantInfo(String applicantId, Integer age, Integer annualIncome,
                           Integer creditScore, Integer existingCreditCards,
                           Integer monthlyRent, String employmentStatus,
                           Integer yearsAtCurrentJob) {
            this.applicantId = applicantId;
            this.age = age;
            this.annualIncome = annualIncome;
            this.creditScore = creditScore;
            this.existingCreditCards = existingCreditCards;
            this.monthlyRent = monthlyRent;
            this.employmentStatus = employmentStatus;
            this.yearsAtCurrentJob = yearsAtCurrentJob;
        }
    }

    @Type("FinancialHistory")
    @Description("财务历史记录 / Financial history")
    public static class FinancialHistory {
        @NonNull
        @Description("破产次数 / Bankruptcy count")
        public Integer bankruptcyCount;

        @NonNull
        @Description("逾期付款次数 / Late payments")
        public Integer latePayments;

        @NonNull
        @Description("信用利用率(%) / Credit utilization percentage")
        public Integer utilization;

        @NonNull
        @Description("账户年龄(年) / Account age in years")
        public Integer accountAge;

        @NonNull
        @Description("硬查询次数 / Hard inquiries")
        public Integer hardInquiries;

        public FinancialHistory() {}

        public FinancialHistory(Integer bankruptcyCount, Integer latePayments,
                               Integer utilization, Integer accountAge,
                               Integer hardInquiries) {
            this.bankruptcyCount = bankruptcyCount;
            this.latePayments = latePayments;
            this.utilization = utilization;
            this.accountAge = accountAge;
            this.hardInquiries = hardInquiries;
        }
    }

    @Type("CreditCardOffer")
    @Description("信用卡产品信息 / Credit card offer")
    public static class CreditCardOffer {
        @NonNull
        @Description("产品类型 / Product type (Premium/Standard/Basic)")
        public String productType;

        @NonNull
        @Description("申请额度 / Requested credit limit")
        public Integer requestedLimit;

        @NonNull
        @Description("是否包含奖励 / Has rewards")
        public Boolean hasRewards;

        @NonNull
        @Description("年费 / Annual fee")
        public Integer annualFee;

        public CreditCardOffer() {}

        public CreditCardOffer(String productType, Integer requestedLimit,
                              Boolean hasRewards, Integer annualFee) {
            this.productType = productType;
            this.requestedLimit = requestedLimit;
            this.hasRewards = hasRewards;
            this.annualFee = annualFee;
        }
    }

    @Type("ApprovalDecision")
    @Description("审批决策结果 / Approval decision")
    public static class ApprovalDecision {
        @NonNull
        @Description("是否批准 / Is approved")
        public Boolean approved;

        @NonNull
        @Description("原因说明 / Reason")
        public String reason;

        @NonNull
        @Description("批准额度 / Approved credit limit")
        public Integer approvedLimit;

        @NonNull
        @Description("年化利率(基点) / Annual interest rate in basis points")
        public Integer interestRateAPR;

        @NonNull
        @Description("月费 / Monthly fee")
        public Integer monthlyFee;

        @NonNull
        @Description("信用额度 / Credit line")
        public Integer creditLine;

        @NonNull
        @Description("是否需要押金 / Requires deposit")
        public Boolean requiresDeposit;

        @NonNull
        @Description("押金金额 / Deposit amount")
        public Integer depositAmount;

        public ApprovalDecision() {}

        public ApprovalDecision(Boolean approved, String reason, Integer approvedLimit,
                               Integer interestRateAPR, Integer monthlyFee,
                               Integer creditLine, Boolean requiresDeposit,
                               Integer depositAmount) {
            this.approved = approved;
            this.reason = reason;
            this.approvedLimit = approvedLimit;
            this.interestRateAPR = interestRateAPR;
            this.monthlyFee = monthlyFee;
            this.creditLine = creditLine;
            this.requiresDeposit = requiresDeposit;
            this.depositAmount = depositAmount;
        }
    }
}
