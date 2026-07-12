package io.aster.policy.rest;

import io.aster.policy.test.RedisEnabledTest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST 契约测试 — POST /api/v1/policies/compile（匿名只读源码编译）。
 *
 * 覆盖：匿名访问、成功编译、语法错误结构化诊断（1-based 行列）、依赖别名的源码、
 * 空源码 400、源码超 16KB 400、超限 aliasSet 拒绝。这是 cloud 保存前校验的公共契约。
 */
@QuarkusTest
@RedisEnabledTest
public class PolicyCompileResourceTest {

    @Test
    public void compileValidSource_returnsSuccessNoDiagnostics() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x times 3.",
                  "locale": "en-US" }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("success", is(true));
    }

    @Test
    public void compileSyntaxError_returnsStructuredDiagnostics1Based() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x times .",
                  "locale": "en-US" }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(false))
            .body("diagnostics", not(empty()))
            .body("diagnostics[0].severity", is("error"))
            .body("diagnostics[0].startLine", greaterThanOrEqualTo(1))
            .body("diagnostics[0].startColumn", greaterThanOrEqualTo(1))
            .body("diagnostics[0].message", not(emptyOrNullString()));
    }

    @Test
    public void compileAliasDependentSource_compilesWithAliasSet() {
        // 依赖用户别名的源码：带 aliasSet 编译应成功（否则会被误判解析错误）。
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x multiplied by 3.",
                  "locale": "en-US",
                  "aliasSet": { "TIMES": ["multiplied by"] } }
                """)
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(200)
            .body("success", is(true));
    }

    @Test
    public void compileBlankSource_returns400() {
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("{ \"source\": \"\", \"locale\": \"en-US\" }")
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(400);
    }

    @Test
    public void compileOversizeSource_returns400() {
        // 源码超 16KB 匿名上限 → 400（@Size 校验，防算法复杂度 DoS）。
        String huge = "// " + "x".repeat(17_000) + "\nModule M.";
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("{ \"source\": \"" + huge + "\", \"locale\": \"en-US\" }")
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(400);
    }

    @Test
    public void compileOversizeAliasSet_returns400() {
        // 单别名短语超 256 字符 → 400（防负载塞 aliasSet 绕过源码 16KB 上限）。
        String longPhrase = "x".repeat(300);
        given()
            .contentType(ContentType.JSON)
            .header("X-Tenant-Id", "default")
            .body("""
                { "source": "Module M.\\n\\nRule p given x as Int, produce Int:\\n  Return x times 3.",
                  "locale": "en-US",
                  "aliasSet": { "TIMES": ["%s"] } }
                """.formatted(longPhrase))
        .when()
            .post("/api/v1/policies/compile")
        .then()
            .statusCode(400)
            .body("error", is("alias_set_too_large"));
    }
}
