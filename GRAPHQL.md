# Aster Policy API - GraphQL 接口文档

> 日期：2025-11-05 06:12 NZST  
> 执行者：Codex

与 REST 接口并行的 GraphQL 服务基于 Quarkus SmallRye GraphQL，面向多租户策略评估、策略文档管理与缓存维护场景，提供更灵活的查询能力。

## 快速开始

```bash
# 开发模式（热加载 GraphQL schema）
./gradlew :quarkus-policy-api:quarkusDev

# 生产模式
./gradlew :quarkus-policy-api:quarkusBuild
java -jar quarkus-policy-api/build/quarkus-app/quarkus-run.jar
```

服务默认监听 `http://localhost:8080`。

## 接入方式与工具

- **GraphQL Endpoint**：`http://localhost:8080/graphql`
- **GraphQL UI（类似 GraphiQL）**：`http://localhost:8080/graphql-ui/`
  - 在 UI 右上角的 “HTTP Headers” 面板中填写 `{"X-Tenant-Id":"tenant-id"}` 可切换租户
  - UI 支持保存示例查询、在 Schema 侧栏浏览类型定义
- **GraphQL SDL 下载**：UI 页面左侧 “Docs” 面板或执行 `curl http://localhost:8080/graphql/schema.graphql`（仅开发模式）

## 多租户支持

所有 GraphQL 请求均可通过 `X-Tenant-Id` 头部指定租户，默认值为 `default`。缓存、策略文档和执行上下文均按租户隔离。

```bash
curl http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: production" \
  -d '{"query":"{ listPolicies { id name } }"}'
```

> **注意**：未显式提供租户时，系统会回落到 `default` 租户，避免不同租户之间的策略与缓存互相污染。

## GraphQL 查询（11 项）

所有查询均返回 Mutiny `Uni` 包装的结果，GraphQL 层会自动展开为标准 JSON。字段说明基于 `io.aster.policy.graphql.types` 包内的类型定义。

### 1. `generateLifeQuote`

- **签名**：`generateLifeQuote(applicant: LifeInsuranceApplicant!, request: LifeInsurancePolicyRequest!): LifeInsuranceQuote!`
- **用途**：生成人寿保险报价。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `applicant` | `LifeInsuranceApplicant` | 是 | 申请人基本信息（年龄、BMI、职业等） |
| `request` | `LifeInsurancePolicyRequest` | 是 | 保额、期限、保单类型 |

**返回结构 `LifeInsuranceQuote`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `approved` | `Boolean!` | 是否批准 |
| `reason` | `String!` | 审批原因说明 |
| `monthlyPremium` | `Int!` | 月保费（单位：本币） |
| `coverageAmount` | `Int!` | 覆盖额度 |
| `termYears` | `Int!` | 保险年限 |

```graphql
query GenerateLifeQuote {
  generateLifeQuote(
    applicant: {
      applicantId: "LIFE-1001"
      age: 36
      gender: "F"
      smoker: false
      bmi: 24
      occupation: "Office"
      healthScore: 82
    }
    request: {
      coverageAmount: 1000000
      termYears: 20
      policyType: "Standard"
    }
  ) {
    approved
    reason
    monthlyPremium
    coverageAmount
    termYears
  }
}
```

```json
{
  "data": {
    "generateLifeQuote": {
      "approved": true,
      "reason": "Quote approved",
      "monthlyPremium": 420,
      "coverageAmount": 1000000,
      "termYears": 20
    }
  }
}
```

### 2. `calculateLifeRiskScore`

- **签名**：`calculateLifeRiskScore(applicant: LifeInsuranceApplicant!): Int!`
- **用途**：计算人寿保险风险评分（数值越高风险越大）。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `applicant` | `LifeInsuranceApplicant` | 是 | 申请人健康与行为信息 |

**返回值**：`Int!`，表示风险分。

```graphql
query LifeRisk {
  calculateLifeRiskScore(
    applicant: {
      applicantId: "LIFE-1002"
      age: 58
      gender: "M"
      smoker: true
      bmi: 32
      occupation: "ModerateRisk"
      healthScore: 55
    }
  )
}
```

```json
{
  "data": {
    "calculateLifeRiskScore": 105
  }
}
```

### 3. `generateAutoQuote`

- **签名**：`generateAutoQuote(driver: AutoInsuranceDriver!, vehicle: AutoInsuranceVehicle!, coverageType: String!): AutoInsurancePolicyQuote!`
- **用途**：生成汽车保险报价。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `driver` | `AutoInsuranceDriver` | 是 | 驾驶员驾驶记录与信用评分 |
| `vehicle` | `AutoInsuranceVehicle` | 是 | 车辆信息（VIN、价值、年份等） |
| `coverageType` | `String!` | 是 | 可选 `Premium`/`Standard`/`Basic` |

**返回结构 `AutoInsurancePolicyQuote`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `approved` | `Boolean!` | 是否通过 |
| `reason` | `String!` | 判定原因 |
| `monthlyPremium` | `Int!` | 月保费 |
| `deductible` | `Int!` | 免赔额 |
| `coverageLimit` | `Int!` | 赔付上限 |

```graphql
query AutoQuote {
  generateAutoQuote(
    driver: {
      driverId: "DRV-9001"
      age: 30
      yearsLicensed: 10
      accidentCount: 0
      violationCount: 1
      creditScore: 720
    }
    vehicle: {
      vin: "1HGCM82633A004352"
      year: 2022
      make: "Aster"
      model: "Falcon"
      value: 320000
      safetyRating: 9
    }
    coverageType: "Premium"
  ) {
    approved
    reason
    monthlyPremium
    deductible
    coverageLimit
  }
}
```

```json
{
  "data": {
    "generateAutoQuote": {
      "approved": true,
      "reason": "Driver history within premium threshold",
      "monthlyPremium": 680,
      "deductible": 2500,
      "coverageLimit": 600000
    }
  }
}
```

### 4. `checkServiceEligibility`

- **签名**：`checkServiceEligibility(patient: HealthcarePatient!, service: HealthcareService!): HealthcareEligibilityCheck!`
- **用途**：校验医疗服务资格与自付金额。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `patient` | `HealthcarePatient` | 是 | 患者年龄、保单类型、慢性病数量等 |
| `service` | `HealthcareService` | 是 | 医疗服务代码、基础费用、是否需要预授权 |

**返回结构 `HealthcareEligibilityCheck`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `eligible` | `Boolean!` | 是否符合资格 |
| `reason` | `String!` | 审核理由 |
| `coveragePercent` | `Int!` | 保险覆盖比例 |
| `estimatedCost` | `Int!` | 患者自付金额 |
| `requiresPreAuth` | `Boolean!` | 是否需要预授权 |

```graphql
query HealthcareEligibility {
  checkServiceEligibility(
    patient: {
      patientId: "PAT-501"
      age: 42
      insuranceType: "Premium"
      hasInsurance: true
      chronicConditions: 1
      accountBalance: 42000
    }
    service: {
      serviceCode: "XRAY-CT"
      serviceName: "CT Scan"
      basePrice: 8500
      requiresPreAuth: false
    }
  ) {
    eligible
    reason
    coveragePercent
    estimatedCost
    requiresPreAuth
  }
}
```

```json
{
  "data": {
    "checkServiceEligibility": {
      "eligible": true,
      "reason": "Premium coverage available",
      "coveragePercent": 90,
      "estimatedCost": 850,
      "requiresPreAuth": false
    }
  }
}
```

### 5. `processClaim`

- **签名**：`processClaim(claim: HealthcareClaim!, provider: HealthcareProvider!, patientCoverage: Int!): HealthcareClaimDecision!`
- **用途**：处理医疗理赔，返回审批结果与金额。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `claim` | `HealthcareClaim` | 是 | 理赔单细节、金额、诊断码等 |
| `provider` | `HealthcareProvider` | 是 | 医疗服务提供者网络属性、评分 |
| `patientCoverage` | `Int!` | 是 | 患者保额比例（0-100） |

**返回结构 `HealthcareClaimDecision`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `approved` | `Boolean!` | 是否批准 |
| `reason` | `String!` | 说明 |
| `approvedAmount` | `Int!` | 批准金额 |
| `requiresReview` | `Boolean!` | 是否需要人工复核 |
| `denialCode` | `String` | 若拒赔则包含编码 |

```graphql
query ProcessClaim {
  processClaim(
    claim: {
      claimId: "CLM-7001"
      amount: 15000
      serviceDate: "2025-10-15"
      specialtyType: "Orthopedics"
      diagnosisCode: "M16.9"
      hasDocumentation: true
      patientId: "PAT-501"
      providerId: "PROV-209"
    }
    provider: {
      providerId: "PROV-209"
      inNetwork: true
      qualityScore: 88
      specialtyType: "Orthopedics"
    }
    patientCoverage: 80
  ) {
    approved
    reason
    approvedAmount
    requiresReview
    denialCode
  }
}
```

```json
{
  "data": {
    "processClaim": {
      "approved": true,
      "reason": "Within coverage and documentation complete",
      "approvedAmount": 12000,
      "requiresReview": false,
      "denialCode": null
    }
  }
}
```

### 6. `evaluateLoanEligibility`

- **签名**：`evaluateLoanEligibility(application: LoanApplicationInfo!, applicant: LoanApplicant!): LoanDecision!`
- **用途**：评估零售贷款申请。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `application` | `LoanApplicationInfo` | 是 | 贷款金额、期限、用途 |
| `applicant` | `LoanApplicant` | 是 | 借款人收入、信用、在职年限 |

**返回结构 `LoanDecision`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `approved` | `Boolean!` | 是否批准 |
| `reason` | `String!` | 判定原因 |
| `maxApprovedAmount` | `Int!` | 最大批准金额 |
| `interestRateBps` | `Int!` | 利率（基点） |
| `termMonths` | `Int!` | 批准期限（月） |

```graphql
query LoanDecision {
  evaluateLoanEligibility(
    application: {
      loanId: "LOAN-3101"
      applicantId: "AP-1001"
      amountRequested: 250000
      purposeCode: "HOME"
      termMonths: 360
    }
    applicant: {
      applicantId: "AP-1001"
      age: 34
      annualIncome: 520000
      creditScore: 735
      existingDebtMonthly: 7000
      yearsEmployed: 6
    }
  ) {
    approved
    reason
    maxApprovedAmount
    interestRateBps
    termMonths
  }
}
```

```json
{
  "data": {
    "evaluateLoanEligibility": {
      "approved": true,
      "reason": "Debt-to-income ratio within threshold",
      "maxApprovedAmount": 260000,
      "interestRateBps": 545,
      "termMonths": 360
    }
  }
}
```

### 7. `evaluateCreditCardApplication`

- **签名**：`evaluateCreditCardApplication(applicant: CreditCardApplicant!, history: FinancialHistory!, offer: CreditCardOffer!): ApprovalDecision!`
- **用途**：审批信用卡申请。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `applicant` | `CreditCardApplicant` | 是 | 人口统计与就业信息 |
| `history` | `FinancialHistory` | 是 | 信用历史与利用率数据 |
| `offer` | `CreditCardOffer` | 是 | 产品类型、申请额度、年费等 |

**返回结构 `ApprovalDecision`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `approved` | `Boolean!` | 是否批准 |
| `reason` | `String!` | 审批说明 |
| `approvedLimit` | `Int!` | 批准额度 |
| `interestRateAPR` | `Int!` | 年化利率（基点） |
| `monthlyFee` | `Int!` | 月管理费 |
| `creditLine` | `Int!` | 总授信额度 |
| `requiresDeposit` | `Boolean!` | 是否需要押金 |
| `depositAmount` | `Int!` | 押金金额 |

```graphql
query CreditCardDecision {
  evaluateCreditCardApplication(
    applicant: {
      applicantId: "CC-2001"
      age: 28
      annualIncome: 320000
      creditScore: 710
      existingCreditCards: 1
      monthlyRent: 6500
      employmentStatus: "Full-time"
      yearsAtCurrentJob: 3
    }
    history: {
      bankruptcyCount: 0
      latePayments: 0
      utilization: 32
      accountAge: 6
      hardInquiries: 1
    }
    offer: {
      productType: "Premium"
      requestedLimit: 80000
      hasRewards: true
      annualFee: 1200
    }
  ) {
    approved
    reason
    approvedLimit
    interestRateAPR
    monthlyFee
    creditLine
    requiresDeposit
    depositAmount
  }
}
```

```json
{
  "data": {
    "evaluateCreditCardApplication": {
      "approved": true,
      "reason": "Applicant meets premium underwriting threshold",
      "approvedLimit": 72000,
      "interestRateAPR": 1599,
      "monthlyFee": 0,
      "creditLine": 72000,
      "requiresDeposit": false,
      "depositAmount": 0
    }
  }
}
```

### 8. `evaluateEnterpriseLoan`

- **签名**：`evaluateEnterpriseLoan(enterprise: EnterpriseInfo!, position: FinancialPosition!, history: BusinessHistory!, application: EnterpriseLoanApplication!): EnterpriseLendingDecision!`
- **用途**：评估企业贷款申请，支持财务、历史与抵押综合判断。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `enterprise` | `EnterpriseInfo` | 是 | 企业基本信息与经营状况 |
| `position` | `FinancialPosition` | 是 | 资产、负债、现金流 |
| `history` | `BusinessHistory` | 是 | 历史借贷行为 |
| `application` | `EnterpriseLoanApplication` | 是 | 贷款金额、期限、抵押物等 |

**返回结构 `EnterpriseLendingDecision`**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `approved` | `Boolean!` | 是否批准 |
| `approvedAmount` | `Int!` | 批准金额 |
| `interestRateBps` | `Int!` | 利率（基点） |
| `termMonths` | `Int!` | 期限（月） |
| `collateralRequired` | `Int!` | 要求抵押物价值 |
| `specialConditions` | `String!` | 附加条件 |
| `riskCategory` | `String!` | 风险分类 |
| `confidenceScore` | `Int!` | 决策信心评分 |
| `reasonCode` | `String!` | 理由代码 |
| `detailedAnalysis` | `String!` | 详细分析文本 |

```graphql
query EnterpriseLoan {
  evaluateEnterpriseLoan(
    enterprise: {
      companyId: "ENT-5501"
      companyName: "Aster Robotics"
      industry: "Manufacturing"
      yearsInBusiness: 12
      employeeCount: 240
      annualRevenue: 18500000
      revenueGrowthRate: 12
      profitMargin: 14
    }
    position: {
      totalAssets: 32000000
      currentAssets: 9000000
      totalLiabilities: 12000000
      currentLiabilities: 4500000
      equity: 20000000
      cashFlow: 2100000
      outstandingDebt: 6000000
    }
    history: {
      previousLoans: 3
      defaultCount: 0
      latePayments: 1
      creditUtilization: 45
      largestLoanAmount: 6000000
      relationshipYears: 5
    }
    application: {
      requestedAmount: 7500000
      loanPurpose: "FactoryExpansion"
      termMonths: 84
      collateralValue: 12000000
      guarantorCount: 1
    }
  ) {
    approved
    approvedAmount
    interestRateBps
    termMonths
    collateralRequired
    specialConditions
    riskCategory
    confidenceScore
    reasonCode
    detailedAnalysis
  }
}
```

```json
{
  "data": {
    "evaluateEnterpriseLoan": {
      "approved": true,
      "approvedAmount": 7000000,
      "interestRateBps": 460,
      "termMonths": 84,
      "collateralRequired": 10000000,
      "specialConditions": "Quarterly financial statements required",
      "riskCategory": "Medium-Low",
      "confidenceScore": 82,
      "reasonCode": "A1",
      "detailedAnalysis": "Positive cash flow and collateral coverage meet underwriting threshold"
    }
  }
}
```

### 9. `evaluatePersonalLoan`

- **签名**：`evaluatePersonalLoan(personal: PersonalInfo!, income: IncomeProfile!, credit: CreditProfile!, debt: DebtProfile!, request: PersonalLoanRequest!): PersonalLoanDecision!`
- **用途**：综合评估个人贷款（高维度输入）。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `personal` | `PersonalInfo` | 是 | 个人基本信息、婚姻、赡养关系 |
| `income` | `IncomeProfile` | 是 | 收入与稳定性指标 |
| `credit` | `CreditProfile` | 是 | 信用评分、历史、违约情况 |
| `debt` | `DebtProfile` | 是 | 各类债务与月供 |
| `request` | `PersonalLoanRequest` | 是 | 贷款金额、用途、抵押等 |

**返回结构 `PersonalLoanDecision`**

包含审批结果、利率、月供、风险等级、建议等 11 个字段。

```graphql
query PersonalLoan {
  evaluatePersonalLoan(
    personal: {
      applicantId: "PL-301"
      age: 33
      educationLevel: "Bachelor"
      employmentStatus: "Full-time"
      occupation: "Software Engineer"
      yearsAtJob: 5
      monthsAtAddress: 24
      maritalStatus: "Married"
      dependents: 2
    }
    income: {
      monthlyIncome: 38000
      additionalIncome: 4000
      spouseIncome: 32000
      rentIncome: 0
      incomeStability: "Stable"
      incomeGrowthRate: 5
    }
    credit: {
      creditScore: 745
      creditHistory: 96
      activeLoans: 2
      creditCardCount: 3
      creditUtilization: 28
      latePayments: 0
      defaults: 0
      bankruptcies: 0
      inquiries: 1
    }
    debt: {
      totalMonthlyDebt: 12000
      mortgagePayment: 8000
      carPayment: 1500
      studentLoanPayment: 1500
      creditCardMinPayment: 800
      otherDebtPayment: 200
      totalOutstandingDebt: 780000
    }
    request: {
      requestedAmount: 250000
      purpose: "HomeRenovation"
      termMonths: 96
      downPayment: 30000
      collateralValue: 300000
    }
  ) {
    approved
    approvedAmount
    interestRateBps
    termMonths
    monthlyPayment
    downPaymentRequired
    conditions
    riskLevel
    decisionScore
    reasonCode
    recommendations
  }
}
```

```json
{
  "data": {
    "evaluatePersonalLoan": {
      "approved": true,
      "approvedAmount": 240000,
      "interestRateBps": 610,
      "termMonths": 96,
      "monthlyPayment": 3400,
      "downPaymentRequired": 20000,
      "conditions": "Maintain DTI < 35%",
      "riskLevel": "Low",
      "decisionScore": 79,
      "reasonCode": "PL-A1",
      "recommendations": "Consider auto-pay enrollment for rate discount"
    }
  }
}
```

### 10. `getPolicy`

- **签名**：`getPolicy(id: String!): Policy!`
- **用途**：根据 ID 读取策略文档（含 allow/deny 规则）。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | `String!` | 是 | 策略唯一标识 |

**返回结构 `Policy`**

- `id`：策略 ID  
- `name`：策略名称  
- `allow` / `deny`：`PolicyRuleSet`，包含 `rules` 数组，每个元素为 `PolicyRule`（`resourceType`、`patterns`）

```graphql
query SinglePolicy {
  getPolicy(id: "finance-loan-defaults") {
    id
    name
    allow {
      rules {
        resourceType
        patterns
      }
    }
    deny {
      rules {
        resourceType
        patterns
      }
    }
  }
}
```

```json
{
  "data": {
    "getPolicy": {
      "id": "finance-loan-defaults",
      "name": "Default Loan Allow/Deny Rules",
      "allow": {
        "rules": [
          {
            "resourceType": "loan.decision",
            "patterns": ["approved", "conditional"]
          }
        ]
      },
      "deny": {
        "rules": [
          {
            "resourceType": "loan.decision",
            "patterns": ["manualReview", "declined"]
          }
        ]
      }
    }
  }
}
```

### 11. `listPolicies`

- **签名**：`listPolicies: [Policy!]!`
- **用途**：列出当前租户所有策略。

```graphql
query AllPolicies {
  listPolicies {
    id
    name
  }
}
```

```json
{
  "data": {
    "listPolicies": [
      {"id": "finance-loan-defaults", "name": "Default Loan Allow/Deny Rules"},
      {"id": "insurance-life-standard", "name": "Life Insurance Standard Rules"}
    ]
  }
}
```

## GraphQL 变更（Mutations，5 项）

所有变更操作同样支持 `X-Tenant-Id` 头部隔离。

### 1. `createPolicy`

- **签名**：`createPolicy(input: PolicyInput!): Policy!`
- **说明**：创建策略并返回完整实体。若 `input.id` 为空，将由系统生成。

```graphql
mutation CreatePolicy {
  createPolicy(
    input: {
      name: "Loan Manual Overrides"
      allow: {
        rules: [
          {resourceType: "loan.manual", patterns: ["override-approve", "override-conditional"]}
        ]
      }
      deny: {rules: [{resourceType: "loan.manual", patterns: ["override-deny"]}]}
    }
  ) {
    id
    name
  }
}
```

```json
{
  "data": {
    "createPolicy": {
      "id": "loan-manual-overrides",
      "name": "Loan Manual Overrides"
    }
  }
}
```

### 2. `updatePolicy`

- **签名**：`updatePolicy(id: String!, input: PolicyInput!): Policy!`
- **说明**：根据 ID 更新策略内容，`input.id` 可选；若提供则会覆盖。

```graphql
mutation UpdatePolicy {
  updatePolicy(
    id: "finance-loan-defaults"
    input: {
      name: "Default Loan Allow/Deny Rules"
      allow: {
        rules: [
          {resourceType: "loan.decision", patterns: ["approved", "conditional", "auto-approve"]}
        ]
      }
    }
  ) {
    id
    name
    allow {
      rules {
        resourceType
        patterns
      }
    }
  }
}
```

```json
{
  "data": {
    "updatePolicy": {
      "id": "finance-loan-defaults",
      "name": "Default Loan Allow/Deny Rules",
      "allow": {
        "rules": [
          {
            "resourceType": "loan.decision",
            "patterns": ["approved", "conditional", "auto-approve"]
          }
        ]
      }
    }
  }
}
```

### 3. `deletePolicy`

- **签名**：`deletePolicy(id: String!): Boolean!`
- **说明**：删除策略并返回布尔值表示是否删除成功。

```graphql
mutation DeletePolicy {
  deletePolicy(id: "loan-manual-overrides")
}
```

```json
{
  "data": {
    "deletePolicy": true
  }
}
```

### 4. `clearAllCache`

- **签名**：`clearAllCache: CacheOperationResult!`
- **说明**：清空所有策略缓存（对所有租户生效），通常仅限管理用途。

**返回结构 `CacheOperationResult`**

- `success`: `Boolean`  
- `message`: `String`  
- `timestamp`: `Long`（毫秒时间戳）

```graphql
mutation ClearCache {
  clearAllCache {
    success
    message
    timestamp
  }
}
```

```json
{
  "data": {
    "clearAllCache": {
      "success": true,
      "message": "All policy cache cleared successfully",
      "timestamp": 1730776325123
    }
  }
}
```

### 5. `invalidateCache`

- **签名**：`invalidateCache(policyModule: String, policyFunction: String): CacheOperationResult!`
- **说明**：按租户定向清除缓存，可选过滤模块或具体函数。

```graphql
mutation InvalidateCache {
  invalidateCache(policyModule: "aster.finance.loan", policyFunction: "evaluateLoanEligibility") {
    success
    message
    timestamp
  }
}
```

```json
{
  "data": {
    "invalidateCache": {
      "success": true,
      "message": "Cache invalidated for tenant production policy aster.finance.loan.evaluateLoanEligibility",
      "timestamp": 1730776398458
    }
  }
}
```

> **提示**：若某个参数省略，则对应范围会扩大（仅提供模块即清理该模块内全部函数；全部省略则默认为当前租户全部缓存）。

## 错误处理示例

GraphQL 层遵循标准错误结构：`data` 字段可能为 `null` 或部分结果，错误详情位于 `errors` 数组。`extensions` 字段包含政策运行时异常信息，便于排查。

```graphql
query InvalidRequest {
  generateLifeQuote(
    applicant: {
      applicantId: "LIFE-INVALID"
      age: 15
      gender: "F"
      smoker: false
      bmi: 22
      occupation: "Office"
      healthScore: 70
    }
    request: {
      coverageAmount: 6000000
      termYears: 25
      policyType: "Standard"
    }
  ) {
    approved
  }
}
```

```json
{
  "data": {
    "generateLifeQuote": null
  },
  "errors": [
    {
      "message": "GraphQL data fetcher exception",
      "path": ["generateLifeQuote"],
      "extensions": {
        "errorType": "DataFetchingException",
        "detailedMessage": "Coverage exceeds maximum",
        "tenantId": "default"
      }
    }
  ]
}
```

常见异常场景：

- 输入对象缺失字段或类型不匹配 → SmallRye GraphQL 自动返回 400 错误，`extensions` 内会包含字段名。
- 找不到策略或内部计算失败 → `errorType` 将提示 `PolicyExecutionException`，同时带出 tenant 信息便于定位。
- Multi-tenant header 缺失 → 使用默认租户；若策略不存在则返回 `PolicyNotFound` 错误。

## 与 REST API 的协同

- GraphQL 查询 `evaluateLoanEligibility` / `evaluateCreditCardApplication` 与 REST `POST /api/policies/evaluate` 使用同一服务层（`PolicyQueryService`），可无缝共享策略逻辑。
- `createPolicy` / `updatePolicy` / `deletePolicy` 与 REST-ful 策略 CRUD 一致，共享 `PolicyManagementService`。
- `clearAllCache` 与 `invalidateCache` 映射 REST `DELETE /api/policies/cache`，GraphQL 形态更适合集成 UI 或多操作组合。
- GraphQL 可以在单个请求中组合多个策略查询，减少 REST 多次往返；而 REST 更适合简化客户端（无 GraphQL 客户端时）。

> 建议在迁移阶段保持 REST 与 GraphQL 的监控并存，通过 Prometheus (`/q/metrics`) 观察两者的 QPS 与延迟差异。

## 开发与测试指引

- **本地开发**：`./gradlew :quarkus-policy-api:quarkusDev` 启动后，访问 GraphQL UI 验证 schema；如需调试输入，可使用浏览器开发者工具观察实际请求体。
- **自动化测试**：运行 `./gradlew :quarkus-policy-api:test`，内置测试覆盖核心策略与服务层逻辑。GraphQL endpoint 可通过 RESTAssured + GraphQL 数据加载器编写扩展测试。
- **cURL 调试模板**：

  ```bash
  curl http://localhost:8080/graphql \
    -H "Content-Type: application/json" \
    -H "X-Tenant-Id: staging" \
    -d '{
      "query": "query($module: String!) { listPolicies { id name } }",
      "variables": {"module": "aster.finance.loan"}
    }'
  ```

- **热重载提示**：GraphQL 类型变更后按 `s` 触发 Quarkus Dev 模式的增量编译，可即刻在 GraphQL UI 中查看更新。
- **远程环境验证**：部署到测试环境后，可借助 Postman / Insomnia 的 GraphQL 模式，或在 CI 流水线中使用 `graphql-cli` 进行契约验证（建议缓存 `schema.graphql` 作为基准）。

## 附录：主要类型速览

| 模块 | 输入类型 | 返回类型 |
| --- | --- | --- |
| `LifeInsuranceTypes` | `Applicant`, `PolicyRequest` | `Quote` |
| `AutoInsuranceTypes` | `Driver`, `Vehicle` | `PolicyQuote` |
| `HealthcareTypes` | `Patient`, `Service`, `Claim`, `Provider` | `EligibilityCheck`, `ClaimDecision` |
| `LoanTypes` | `Application`, `Applicant` | `Decision` |
| `CreditCardTypes` | `ApplicantInfo`, `FinancialHistory`, `CreditCardOffer` | `ApprovalDecision` |
| `EnterpriseLendingTypes` | `EnterpriseInfo`, `FinancialPosition`, `BusinessHistory`, `LoanApplication` | `LendingDecision` |
| `PersonalLendingTypes` | `PersonalInfo`, `IncomeProfile`, `CreditProfile`, `DebtProfile`, `LoanRequest` | `LoanDecision` |
| `PolicyTypes` | `PolicyInput`, `PolicyRuleSetInput`, `PolicyRuleInput` | `Policy`, `PolicyRuleSet`, `PolicyRule` |
| `CacheManagementService` | - | `CacheOperationResult` |

> 所有类型的字段注释与约束在源码包 `io.aster.policy.graphql.types` 中可查，与此文档保持一致。
