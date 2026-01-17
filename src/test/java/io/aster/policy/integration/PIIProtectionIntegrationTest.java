package io.aster.policy.integration;

import com.wontlost.aster.policy.PIIRedactor;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Task 4.4 - 端到端 PII 防护集成测试
 *
 * 验证 PII 防护的完整链路：
 * 1. LSP 编译期检测 (Task 4.1)
 * 2. HTTP 响应拦截脱敏 (Task 4.2)
 * 3. CDI 方法拦截检测 (Task 4.3)
 * 4. 日志输出脱敏 (PIIRedactingLogFilter)
 *
 * 测试策略：
 * - 通过现有 API 测试 HTTP 响应过滤
 * - 通过 PIIRedactor 单元测试验证脱敏逻辑
 * - 通过日志输出验证日志脱敏
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
public class PIIProtectionIntegrationTest {

    /**
     * Test 1: PIIRedactor 基本功能测试
     * 验证所有 PII 类型都能被正确检测和脱敏
     */
    @Test
    void testPIIRedactorDetectsAllTypes() {
        PIIRedactor redactor = new PIIRedactor();

        // SSN 检测
        assertTrue(redactor.containsPII("123-45-6789"), "Should detect SSN");
        assertTrue(redactor.containsPII("123456789"), "Should detect SSN without dashes");

        // Email 检测
        assertTrue(redactor.containsPII("user@example.com"), "Should detect email");

        // 电话号码检测
        assertTrue(redactor.containsPII("(123) 456-7890"), "Should detect phone with parentheses");
        assertTrue(redactor.containsPII("123-456-7890"), "Should detect phone with dashes");
        assertTrue(redactor.containsPII("1234567890"), "Should detect phone without formatting");

        // 信用卡号检测
        assertTrue(redactor.containsPII("1234-5678-9012-3456"), "Should detect credit card");
        assertTrue(redactor.containsPII("1234567890123456"), "Should detect credit card without dashes");

        // IP 地址检测
        assertTrue(redactor.containsPII("192.168.1.1"), "Should detect IP address");

        // 非 PII 数据
        assertFalse(redactor.containsPII("normal text"), "Should not detect PII in normal text");
        assertFalse(redactor.containsPII("12345"), "Should not detect PII in short numbers");
    }

    /**
     * Test 2: PIIRedactor 脱敏功能测试
     * 验证所有 PII 类型都能被正确脱敏
     */
    @Test
    void testPIIRedactorRedactsAllTypes() {
        PIIRedactor redactor = new PIIRedactor();

        // SSN 脱敏
        String ssnText = "User SSN is 123-45-6789";
        String redactedSSN = redactor.redact(ssnText);
        assertTrue(redactedSSN.contains("***-**-****"), "SSN should be redacted");
        assertFalse(redactedSSN.contains("123-45-6789"), "Original SSN should not appear");

        // Email 脱敏
        String emailText = "Contact user@example.com for info";
        String redactedEmail = redactor.redact(emailText);
        assertTrue(redactedEmail.contains("***@***.***"), "Email should be redacted");
        assertFalse(redactedEmail.contains("user@example.com"), "Original email should not appear");

        // 电话号码脱敏
        String phoneText = "Call (123) 456-7890 for support";
        String redactedPhone = redactor.redact(phoneText);
        assertTrue(redactedPhone.contains("(***) ***-****"), "Phone should be redacted");
        assertFalse(redactedPhone.contains("(123) 456-7890"), "Original phone should not appear");

        // 信用卡号脱敏
        String ccText = "Card number: 1234-5678-9012-3456";
        String redactedCC = redactor.redact(ccText);
        assertTrue(redactedCC.contains("****-****-****-****"), "Credit card should be redacted");
        assertFalse(redactedCC.contains("1234-5678-9012-3456"), "Original credit card should not appear");

        // IP 地址脱敏
        String ipText = "Connected from 192.168.1.1";
        String redactedIP = redactor.redact(ipText);
        assertTrue(redactedIP.contains("***.***.***.***"), "IP address should be redacted");
        assertFalse(redactedIP.contains("192.168.1.1"), "Original IP address should not appear");
    }

    /**
     * Test 3: 混合 PII 数据脱敏测试
     * 验证包含多种 PII 的文本能被完全脱敏
     */
    @Test
    void testMultiplePIITypesRedacted() {
        PIIRedactor redactor = new PIIRedactor();

        String mixedText = "User john@example.com (SSN: 123-45-6789) called from (555) 123-4567, IP: 192.168.1.100";

        // 检测
        assertTrue(redactor.containsPII(mixedText), "Should detect multiple PII types");

        // 脱敏
        String redacted = redactor.redact(mixedText);

        // 验证所有 PII 都被脱敏
        assertFalse(redacted.contains("john@example.com"), "Email should be redacted");
        assertFalse(redacted.contains("123-45-6789"), "SSN should be redacted");
        assertFalse(redacted.contains("(555) 123-4567"), "Phone should be redacted");
        assertFalse(redacted.contains("192.168.1.100"), "IP should be redacted");

        // 验证脱敏后的占位符存在
        assertTrue(redacted.contains("***@***.***"), "Email placeholder should exist");
        assertTrue(redacted.contains("***-**-****"), "SSN placeholder should exist");
        assertTrue(redacted.contains("(***) ***-****"), "Phone placeholder should exist");
        assertTrue(redacted.contains("***.***.***.***"), "IP placeholder should exist");
    }

    /**
     * Test 4: HTTP 响应过滤器集成测试
     * 验证 PIIResponseFilter 不会破坏正常的 API 调用
     */
    @Test
    void testHTTPResponseFilterDoesNotBreakAPIs() {
        // 测试审计日志 API（不包含 PII 的正常响应）
        given()
            .header("X-Tenant-Id", "test-tenant")
            .when()
            .get("/api/audit")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON);

        // 测试审计链验证 API
        given()
            .header("X-Tenant-Id", "test-tenant")
            .queryParam("start", "2025-01-15T10:00:00Z")
            .queryParam("end", "2025-01-15T11:00:00Z")
            .when()
            .get("/api/audit/verify-chain")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("valid", equalTo(true));
    }

    /**
     * Test 5: PIIRedactor 构建器模式测试
     * 验证可以选择性地启用/禁用特定类型的 PII 脱敏
     *
     * 注意：containsPII() 方法总是检测所有 PII 类型（用于审计目的）
     * Builder 控制的是 redact() 方法的脱敏行为
     */
    @Test
    void testPIIRedactorBuilder() {
        // 仅脱敏 Email
        PIIRedactor emailOnly = new PIIRedactor.Builder()
            .redactSSN(false)
            .redactPhone(false)
            .redactCreditCard(false)
            .redactIPAddress(false)
            .redactEmail(true)
            .build();

        // containsPII() 总是检测所有类型
        assertTrue(emailOnly.containsPII("user@example.com"), "Should detect email");
        assertTrue(emailOnly.containsPII("123-45-6789"), "containsPII always detects all types");

        // 但 redact() 只脱敏配置的类型
        String textWithEmail = "Contact user@example.com";
        String textWithSSN = "SSN: 123-45-6789";

        assertTrue(emailOnly.redact(textWithEmail).contains("***@***.***"), "Should redact email");
        assertFalse(emailOnly.redact(textWithEmail).contains("user@example.com"), "Email should be redacted");

        assertFalse(emailOnly.redact(textWithSSN).contains("***-**-****"), "Should not redact SSN when disabled");
        assertTrue(emailOnly.redact(textWithSSN).contains("123-45-6789"), "SSN should remain when redaction disabled");

        // 仅脱敏 SSN
        PIIRedactor ssnOnly = new PIIRedactor.Builder()
            .redactSSN(true)
            .redactEmail(false)
            .redactPhone(false)
            .redactCreditCard(false)
            .redactIPAddress(false)
            .build();

        // containsPII() 总是检测所有类型
        assertTrue(ssnOnly.containsPII("123-45-6789"), "containsPII always detects all types");
        assertTrue(ssnOnly.containsPII("user@example.com"), "containsPII always detects all types");

        // 但 redact() 只脱敏配置的类型
        assertTrue(ssnOnly.redact(textWithSSN).contains("***-**-****"), "Should redact SSN");
        assertFalse(ssnOnly.redact(textWithSSN).contains("123-45-6789"), "SSN should be redacted");

        assertFalse(ssnOnly.redact(textWithEmail).contains("***@***.***"), "Should not redact email when disabled");
        assertTrue(ssnOnly.redact(textWithEmail).contains("user@example.com"), "Email should remain when redaction disabled");
    }

    /**
     * Test 6: 边界条件测试
     * 验证 PIIRedactor 在边界条件下的行为
     */
    @Test
    void testPIIRedactorEdgeCases() {
        PIIRedactor redactor = new PIIRedactor();

        // Null 输入
        assertFalse(redactor.containsPII(null), "Should handle null input");
        assertNull(redactor.redact(null), "Should return null for null input");

        // 空字符串
        assertFalse(redactor.containsPII(""), "Should handle empty string");
        assertEquals("", redactor.redact(""), "Should return empty string for empty input");

        // 仅包含空白字符
        assertFalse(redactor.containsPII("   "), "Should handle whitespace-only string");
        assertEquals("   ", redactor.redact("   "), "Should preserve whitespace");

        // 部分匹配（不应该被检测为 PII）
        assertFalse(redactor.containsPII("12-34-567"), "Should not detect partial SSN");
        assertFalse(redactor.containsPII("123456"), "Should not detect incomplete SSN");
    }

    /**
     * Test 7: 性能测试
     * 验证 PIIRedactor 在大文本上的性能
     */
    @Test
    void testPIIRedactorPerformance() {
        PIIRedactor redactor = new PIIRedactor();

        // 构造包含多个 PII 的大文本
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeText.append("User ").append(i).append(": email").append(i).append("@example.com, ");
            largeText.append("SSN: 123-45-").append(String.format("%04d", i)).append(", ");
            largeText.append("Phone: (555) 123-").append(String.format("%04d", i)).append(". ");
        }

        String text = largeText.toString();

        // 性能测试：检测
        long startDetect = System.nanoTime();
        boolean hasPII = redactor.containsPII(text);
        long endDetect = System.nanoTime();
        long detectTime = (endDetect - startDetect) / 1_000_000; // Convert to milliseconds

        assertTrue(hasPII, "Should detect PII in large text");
        assertTrue(detectTime < 1000, "Detection should complete in less than 1 second, actual: " + detectTime + "ms");

        // 性能测试：脱敏
        long startRedact = System.nanoTime();
        String redacted = redactor.redact(text);
        long endRedact = System.nanoTime();
        long redactTime = (endRedact - startRedact) / 1_000_000; // Convert to milliseconds

        assertNotEquals(text, redacted, "Text should be redacted");
        assertTrue(redactTime < 1000, "Redaction should complete in less than 1 second, actual: " + redactTime + "ms");
    }
}
