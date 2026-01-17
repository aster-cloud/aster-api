package io.aster.policy.graphql.types;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * GraphQL类型定义 - 人寿保险
 */
public class LifeInsuranceTypes {

    @Type("LifeInsuranceApplicant")
    @Description("人寿保险申请人信息 / Life insurance applicant information")
    public static class Applicant {
        @NonNull
        @Description("申请人ID / Applicant ID")
        public String applicantId;

        @NonNull
        @Description("年龄 / Age (18-80)")
        public Integer age;

        @NonNull
        @Description("性别 / Gender (M/F)")
        public String gender;

        @NonNull
        @Description("是否吸烟 / Is smoker")
        public Boolean smoker;

        @NonNull
        @Description("身体质量指数 / Body Mass Index")
        public Integer bmi;

        @NonNull
        @Description("职业 / Occupation (Office/ModerateRisk/HighRisk)")
        public String occupation;

        @NonNull
        @Description("健康评分 / Health score (0-100)")
        public Integer healthScore;

        // Default constructor required by GraphQL
        public Applicant() {}

        public Applicant(String applicantId, Integer age, String gender, Boolean smoker,
                        Integer bmi, String occupation, Integer healthScore) {
            this.applicantId = applicantId;
            this.age = age;
            this.gender = gender;
            this.smoker = smoker;
            this.bmi = bmi;
            this.occupation = occupation;
            this.healthScore = healthScore;
        }
    }

    @Type("LifeInsurancePolicyRequest")
    @Description("人寿保险保单请求 / Life insurance policy request")
    public static class PolicyRequest {
        @NonNull
        @Description("保额 / Coverage amount")
        public Integer coverageAmount;

        @NonNull
        @Description("保险期限（年）/ Term in years")
        public Integer termYears;

        @NonNull
        @Description("保单类型 / Policy type")
        public String policyType;

        // Default constructor required by GraphQL
        public PolicyRequest() {}

        public PolicyRequest(Integer coverageAmount, Integer termYears, String policyType) {
            this.coverageAmount = coverageAmount;
            this.termYears = termYears;
            this.policyType = policyType;
        }
    }

    @Type("LifeInsuranceQuote")
    @Description("人寿保险报价结果 / Life insurance quote result")
    public static class Quote {
        @NonNull
        @Description("是否批准 / Is approved")
        public Boolean approved;

        @NonNull
        @Description("原因说明 / Reason")
        public String reason;

        @NonNull
        @Description("月保费 / Monthly premium")
        public Integer monthlyPremium;

        @NonNull
        @Description("保额 / Coverage amount")
        public Integer coverageAmount;

        @NonNull
        @Description("保险期限 / Term in years")
        public Integer termYears;

        // Default constructor required by GraphQL
        public Quote() {}

        public Quote(Boolean approved, String reason, Integer monthlyPremium,
                    Integer coverageAmount, Integer termYears) {
            this.approved = approved;
            this.reason = reason;
            this.monthlyPremium = monthlyPremium;
            this.coverageAmount = coverageAmount;
            this.termYears = termYears;
        }
    }
}
