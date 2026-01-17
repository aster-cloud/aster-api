package io.aster.policy.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class DebugApiTest {

    @Test
    void debugApiResponse() {
        String request = """
            {
                "policyModule": "aster.finance.loan",
                "policyFunction": "evaluateLoanEligibility",
                "context": [
                    {
                        "applicantId": "APP001",
                        "amount": 50000,
                        "termMonths": 36,
                        "purpose": "Home"
                    },
                    {
                        "age": 35,
                        "creditScore": 600,
                        "annualIncome": 80000,
                        "monthlyDebt": 2000,
                        "yearsEmployed": 5
                    }
                ]
            }
            """;

        String response = given()
            .header("X-Tenant-Id", "debug-tenant")
            .contentType(ContentType.JSON)
            .body(request)
            .when()
            .post("/api/policies/evaluate")
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        System.out.println("========== API RESPONSE ==========");
        System.out.println(response);
        System.out.println("==================================");
    }
}
