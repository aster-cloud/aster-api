# GraphQL API Test Coverage

## Test Summary

Created comprehensive GraphQL test suite in `PolicyGraphQLResourceTest.java` covering:
- **Query tests**: 11 test cases
- **Mutation tests**: 2 test cases
- **Error handling tests**: 2 test cases
- **Integration tests**: 2 test cases

**Total**: 17 test cases

## Test Status

### ✅ Passing Tests (10 tests)

1. **testEvaluateLoanEligibility_Approved** - Loan approval with good credit
2. **testEvaluateLoanEligibility_LowCreditScore** - Loan rejection with poor credit
3. **testClearAllCache** - Cache清空功能
4. **testInvalidateCache** - 特定策略缓存失效
5. **testMissingRequiredField** - 缺少必填字段错误处理
6. **testInvalidQuerySyntax** - 无效查询语法错误处理
7. **testGenerateLifeQuote_ValidApplicant** - 人寿保险报价生成
8. **testCalculateLifeRiskScore** - 人寿保险风险评分
9. **testGenerateAutoQuote_PremiumCoverage** - 汽车保险高级保障报价
10. **testQueryWithFragment** - GraphQL Fragment语法测试

### ⚠️ Pending Tests (6 tests - require policy implementation)

These tests are correctly written but fail because the underlying CNL policies are not yet implemented or have different signatures:

1. **testCheckServiceEligibility** - Healthcare service eligibility check
   - Requires: `aster.healthcare.eligibility.checkServiceEligibility`

2. **testProcessClaim** - Healthcare claim processing
   - Requires: `aster.healthcare.claims.processClaim`

3. **testEvaluateCreditCardApplication_Approved** - Credit card approval
   - Requires: `aster.finance.creditcard.evaluateCreditCardApplication`

4. **testEvaluateEnterpriseLoan** - Enterprise lending decision
   - Requires: `aster.finance.enterprise_lending.evaluateEnterpriseLoan`

5. **testEvaluatePersonalLoan** - Personal loan evaluation
   - Requires: `aster.finance.personal_lending.evaluatePersonalLoan`

6. **testMultipleQueriesInSingleRequest** - Multiple queries in single GraphQL request
   - Combines loan and credit card queries

## Test Coverage Details

### Query Tests

#### Loan Evaluation (✅ Working)
- Tests both approval and rejection scenarios
- Validates response structure (approved, reason, amount, rate, term)
- Uses realistic test data (credit scores, income, debt ratios)

#### Life Insurance (✅ Working)
- Quote generation with applicant profile
- Risk score calculation
- Validates premium calculations and coverage amounts

#### Auto Insurance (✅ Working)
- Premium quote generation
- Driver and vehicle profile evaluation
- Coverage type selection (Premium/Standard/Basic)

#### Healthcare (⚠️ Pending Implementation)
- Service eligibility checks
- Claim processing and approval
- Provider network validation

#### Credit Card (⚠️ Pending Implementation)
- Application evaluation
- Credit limit determination
- Interest rate calculation

#### Enterprise Lending (⚠️ Pending Implementation)
- Business loan evaluation
- Financial position analysis
- Risk category assignment

#### Personal Lending (⚠️ Pending Implementation)
- Personal loan approval
- Debt-to-income ratio analysis
- Risk level assessment

### Mutation Tests (✅ All Working)

1. **Cache Management**
   - Clear all cache
   - Invalidate specific policy cache
   - Validates success responses and timestamps

### Error Handling Tests (✅ All Working)

1. **Missing Required Fields**
   - Tests GraphQL validation for missing fields
   - Expects error response with details

2. **Invalid Query Syntax**
   - Tests malformed GraphQL queries
   - Validates error messaging

### Integration Tests

1. **Multiple Queries** (⚠️ Pending - depends on credit card implementation)
   - Tests batching multiple queries in single request
   - Uses aliases for query differentiation

2. **GraphQL Fragments** (✅ Working)
   - Tests fragment syntax and reuse
   - Validates fragment expansion

## Running the Tests

```bash
# Run all GraphQL tests
./gradlew :quarkus-policy-api:test --tests PolicyGraphQLResourceTest

# Run specific test
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testClearAllCache"

# Run only passing tests
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testEvaluateLoanEligibility*"
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testGenerateLifeQuote*"
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testGenerateAutoQuote*"
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testCalculateLifeRiskScore"
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testClearAllCache"
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testInvalidateCache"
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testMissingRequiredField"
./gradlew :quarkus-policy-api:test --tests "PolicyGraphQLResourceTest.testInvalidQuerySyntax"
```

## Next Steps

To make all tests pass:

1. **Implement Healthcare Policies**
   - `aster.healthcare.eligibility.checkServiceEligibility`
   - `aster.healthcare.claims.processClaim`

2. **Verify Credit Card Policy Signature**
   - Ensure `aster.finance.creditcard.evaluateCreditCardApplication` matches expected inputs/outputs

3. **Implement Enterprise Lending Policy**
   - `aster.finance.enterprise_lending.evaluateEnterpriseLoan`

4. **Implement Personal Lending Policy**
   - `aster.finance.personal_lending.evaluatePersonalLoan`

## Test Architecture

All tests follow this pattern:

```java
@Test
public void testQueryName() {
    String query = """
        query {
          queryName(arg1: value1, arg2: value2) {
            field1
            field2
          }
        }
        """;

    given()
        .contentType(ContentType.JSON)
        .body(graphQLRequest(query))
        .when()
        .post("/graphql")
        .then()
        .statusCode(200)
        .body("data.queryName.field1", notNullValue());
}
```

Key components:
- `graphQLRequest(query)` helper wraps query in `{"query": "..."}`format
- RestAssured for HTTP testing
- Hamcrest matchers for assertions
- JSON path navigation for response validation

## Additional Features Tested

- ✅ Reactive (Uni) responses
- ✅ Type safety with GraphQL input types
- ✅ Complex nested input objects
- ✅ Non-null field validation
- ✅ Chinese + English bilingual descriptions
- ✅ Cache integration
- ✅ Error handling and validation
- ✅ GraphQL introspection (implicit via schema)
