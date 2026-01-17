package io.aster.policy.graphql.types;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * GraphQL类型定义 - 贷款评估
 */
public class LoanTypes {

    @Type("LoanApplicationInfo")
    @Description("贷款申请信息 / Loan application information")
    public static class Application {
        @NonNull
        @Description("贷款ID / Loan ID")
        public String loanId;

        @NonNull
        @Description("申请人ID / Applicant ID")
        public String applicantId;

        @NonNull
        @Description("申请金额 / Amount requested")
        public Integer amountRequested;

        @NonNull
        @Description("贷款用途代码 / Purpose code")
        public String purposeCode;

        @NonNull
        @Description("贷款期限(月) / Term in months")
        public Integer termMonths;

        public Application() {}

        public Application(String loanId, String applicantId, Integer amountRequested, String purposeCode, Integer termMonths) {
            this.loanId = loanId;
            this.applicantId = applicantId;
            this.amountRequested = amountRequested;
            this.purposeCode = purposeCode;
            this.termMonths = termMonths;
        }
    }

    @Type("LoanApplicantProfile")
    @Name("LoanApplicant")
    @Description("贷款申请人信息 / Loan applicant profile")
    public static class Applicant {
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
        @Description("月债务 / Existing debt monthly")
        public Integer existingDebtMonthly;

        @NonNull
        @Description("在职年限 / Years employed")
        public Integer yearsEmployed;

        public Applicant() {}

        public Applicant(String applicantId, Integer age, Integer annualIncome,
                        Integer creditScore, Integer existingDebtMonthly, Integer yearsEmployed) {
            this.applicantId = applicantId;
            this.age = age;
            this.annualIncome = annualIncome;
            this.creditScore = creditScore;
            this.existingDebtMonthly = existingDebtMonthly;
            this.yearsEmployed = yearsEmployed;
        }
    }

    @Type("LoanDecision")
    @Description("贷款决策结果 / Loan decision result")
    public static class Decision {
        @NonNull
        @Description("是否批准 / Is approved")
        public Boolean approved;

        @NonNull
        @Description("原因说明 / Reason")
        public String reason;

        @NonNull
        @Description("批准金额 / Approved amount")
        public Integer maxApprovedAmount;

        @NonNull
        @Description("利率(基点) / Interest rate in basis points")
        public Integer interestRateBps;

        @NonNull
        @Description("贷款期限(月) / Term in months")
        public Integer termMonths;

        public Decision() {}

        public Decision(Boolean approved, String reason, Integer maxApprovedAmount,
                       Integer interestRateBps, Integer termMonths) {
            this.approved = approved;
            this.reason = reason;
            this.maxApprovedAmount = maxApprovedAmount;
            this.interestRateBps = interestRateBps;
            this.termMonths = termMonths;
        }
    }
}
