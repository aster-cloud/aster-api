package io.aster.policy.graphql.types;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * GraphQL类型定义 - 医疗健康保险
 */
public class HealthcareTypes {

    @Type("HealthcarePatient")
    @Description("医疗保险患者信息 / Healthcare patient information")
    public static class Patient {
        @NonNull
        @Description("患者ID / Patient ID")
        public String patientId;

        @NonNull
        @Description("年龄 / Age")
        public Integer age;

        @NonNull
        @Description("保险类型 / Insurance type (Premium/Standard/Basic)")
        public String insuranceType;

        @NonNull
        @Description("是否持有保险 / Has insurance")
        public Boolean hasInsurance;

        @NonNull
        @Description("慢性疾病数量 / Chronic conditions count")
        public Integer chronicConditions;

        @NonNull
        @Description("账户余额 / Account balance")
        public Integer accountBalance;

        public Patient() {}

        public Patient(String patientId, Integer age, String insuranceType,
                      Boolean hasInsurance, Integer chronicConditions, Integer accountBalance) {
            this.patientId = patientId;
            this.age = age;
            this.insuranceType = insuranceType;
            this.hasInsurance = hasInsurance;
            this.chronicConditions = chronicConditions;
            this.accountBalance = accountBalance;
        }
    }

    @Type("HealthcareService")
    @Description("医疗服务 / Healthcare service")
    public static class Service {
        @NonNull
        @Description("服务代码 / Service code")
        public String serviceCode;

        @NonNull
        @Description("服务名称 / Service name")
        public String serviceName;

        @NonNull
        @Description("基础价格 / Base price")
        public Integer basePrice;

        @NonNull
        @Description("是否需要预授权 / Requires pre-authorization")
        public Boolean requiresPreAuth;

        public Service() {}

        public Service(String serviceCode, String serviceName, Integer basePrice, Boolean requiresPreAuth) {
            this.serviceCode = serviceCode;
            this.serviceName = serviceName;
            this.basePrice = basePrice;
            this.requiresPreAuth = requiresPreAuth;
        }
    }

    @Type("HealthcareEligibilityCheck")
    @Description("医疗保险资格审查结果 / Healthcare eligibility check result")
    public static class EligibilityCheck {
        @NonNull
        @Description("是否符合资格 / Is eligible")
        public Boolean eligible;

        @NonNull
        @Description("原因说明 / Reason")
        public String reason;

        @NonNull
        @Description("覆盖百分比 / Coverage percentage")
        public Integer coveragePercent;

        @NonNull
        @Description("患者需支付金额 / Patient cost")
        public Integer estimatedCost;

        @NonNull
        @Description("是否需要预授权 / Requires pre-authorization")
        public Boolean requiresPreAuth;

        public EligibilityCheck() {}

        public EligibilityCheck(Boolean eligible, String reason, Integer coveragePercent,
                               Integer estimatedCost, Boolean requiresPreAuth) {
            this.eligible = eligible;
            this.reason = reason;
            this.coveragePercent = coveragePercent;
            this.estimatedCost = estimatedCost;
            this.requiresPreAuth = requiresPreAuth;
        }
    }

    @Type("HealthcareClaim")
    @Description("医疗索赔 / Healthcare claim")
    public static class Claim {
        @NonNull
        @Description("索赔ID / Claim ID")
        public String claimId;

        @NonNull
        @Description("索赔金额 / Claim amount")
        public Integer amount;

        @NonNull
        @Description("服务日期 / Service date")
        public String serviceDate;

        @NonNull
        @Description("专科类型 / Specialty type")
        public String specialtyType;

        @NonNull
        @Description("诊断代码 / Diagnosis code")
        public String diagnosisCode;

        @NonNull
        @Description("是否有文档 / Has documentation")
        public Boolean hasDocumentation;

        @NonNull
        @Description("患者ID / Patient ID")
        public String patientId;

        @NonNull
        @Description("提供者ID / Provider ID")
        public String providerId;

        public Claim() {}

        public Claim(String claimId, Integer amount, String serviceDate,
                    String specialtyType, String diagnosisCode, Boolean hasDocumentation,
                    String patientId, String providerId) {
            this.claimId = claimId;
            this.amount = amount;
            this.serviceDate = serviceDate;
            this.specialtyType = specialtyType;
            this.diagnosisCode = diagnosisCode;
            this.hasDocumentation = hasDocumentation;
            this.patientId = patientId;
            this.providerId = providerId;
        }
    }

    @Type("HealthcareProvider")
    @Description("医疗服务提供者 / Healthcare provider")
    public static class Provider {
        @NonNull
        @Description("提供者ID / Provider ID")
        public String providerId;

        @NonNull
        @Description("是否为网络内 / Is in network")
        public Boolean inNetwork;

        @NonNull
        @Description("质量评分 / Quality score (0-100)")
        public Integer qualityScore;

        @NonNull
        @Description("专科类型 / Specialty type")
        public String specialtyType;

        public Provider() {}

        public Provider(String providerId, Boolean inNetwork, Integer qualityScore, String specialtyType) {
            this.providerId = providerId;
            this.inNetwork = inNetwork;
            this.qualityScore = qualityScore;
            this.specialtyType = specialtyType;
        }
    }

    @Type("HealthcareClaimDecision")
    @Description("医疗索赔决定 / Healthcare claim decision")
    public static class ClaimDecision {
        @NonNull
        @Description("是否批准 / Is approved")
        public Boolean approved;

        @NonNull
        @Description("原因说明 / Reason")
        public String reason;

        @NonNull
        @Description("批准金额 / Approved amount")
        public Integer approvedAmount;

        @NonNull
        @Description("需要审查 / Requires review")
        public Boolean requiresReview;

        @Description("拒赔代码 / Denial code")
        public String denialCode;

        public ClaimDecision() {}

        public ClaimDecision(Boolean approved, String reason, Integer approvedAmount,
                            Boolean requiresReview, String denialCode) {
            this.approved = approved;
            this.reason = reason;
            this.approvedAmount = approvedAmount;
            this.requiresReview = requiresReview;
            this.denialCode = denialCode;
        }
    }
}
