package io.aster.policy.graphql.types;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * GraphQL类型定义 - 汽车保险
 */
public class AutoInsuranceTypes {

    @Type("AutoInsuranceDriver")
    @Description("汽车保险驾驶员信息 / Auto insurance driver information")
    public static class Driver {
        @NonNull
        @Description("驾驶员ID / Driver ID")
        public String driverId;

        @NonNull
        @Description("年龄 / Age")
        public Integer age;

        @NonNull
        @Description("持驾照年限 / Years licensed")
        public Integer yearsLicensed;

        @NonNull
        @Description("事故次数 / Accident count")
        public Integer accidentCount;

        @NonNull
        @Description("违章次数 / Violation count")
        public Integer violationCount;

        @NonNull
        @Description("信用评分 / Credit score")
        public Integer creditScore;

        public Driver() {}

        public Driver(String driverId, Integer age, Integer yearsLicensed,
                     Integer accidentCount, Integer violationCount, Integer creditScore) {
            this.driverId = driverId;
            this.age = age;
            this.yearsLicensed = yearsLicensed;
            this.accidentCount = accidentCount;
            this.violationCount = violationCount;
            this.creditScore = creditScore;
        }
    }

    @Type("AutoInsuranceVehicle")
    @Description("汽车保险车辆信息 / Auto insurance vehicle information")
    public static class Vehicle {
        @NonNull
        @Description("车辆识别号 / Vehicle Identification Number")
        public String vin;

        @NonNull
        @Description("车辆年份 / Vehicle year")
        public Integer year;

        @NonNull
        @Description("制造商 / Make")
        public String make;

        @NonNull
        @Description("型号 / Model")
        public String model;

        @NonNull
        @Description("车辆价值 / Vehicle value")
        public Integer value;

        @NonNull
        @Description("安全评级 / Safety rating (1-10)")
        public Integer safetyRating;

        public Vehicle() {}

        public Vehicle(String vin, Integer year, String make, String model,
                      Integer value, Integer safetyRating) {
            this.vin = vin;
            this.year = year;
            this.make = make;
            this.model = model;
            this.value = value;
            this.safetyRating = safetyRating;
        }
    }

    @Type("AutoInsurancePolicyQuote")
    @Description("汽车保险报价 / Auto insurance policy quote")
    public static class PolicyQuote {
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
        @Description("免赔额 / Deductible")
        public Integer deductible;

        @NonNull
        @Description("保额上限 / Coverage limit")
        public Integer coverageLimit;

        public PolicyQuote() {}

        public PolicyQuote(Boolean approved, String reason, Integer monthlyPremium,
                          Integer deductible, Integer coverageLimit) {
            this.approved = approved;
            this.reason = reason;
            this.monthlyPremium = monthlyPremium;
            this.deductible = deductible;
            this.coverageLimit = coverageLimit;
        }
    }
}
