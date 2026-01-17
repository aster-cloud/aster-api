package io.aster.policy.logging;

import com.wontlost.aster.policy.PIIRedactor;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 Task 4 - PII 敏感信息脱敏测试
 *
 * 直接验证 {@link PIIRedactor#redact(String)} 的掩码输出，规避 Quarkus ClassLoader 与 LogCaptor 的冲突。
 * AuditLogger#toJson() 已使用 PIIRedactor，因此本测试聚焦于掩码规则正确性。
 */
@QuarkusTest
public class PIIRedactionIntegrationTest {

    private PIIRedactor piiRedactor;

    @BeforeEach
    void setup() {
        piiRedactor = new PIIRedactor();
    }

    @Test
    void testSSNRedacted() {
        // Given: 包含 SSN 的消息
        String message = "User SSN: 123-45-6789 registered";

        // When: 执行脱敏
        String redacted = piiRedactor.redact(message);

        // Then: 验证脱敏结果
        assertTrue(redacted.contains("***-**-****"),
            "SSN 应被掩码为 ***-**-****");
        assertFalse(redacted.contains("123-45-6789"),
            "脱敏结果禁止包含原始 SSN");
    }

    @Test
    void testEmailRedacted() {
        // Given
        String message = "User email: user@example.com created";

        // When
        String redacted = piiRedactor.redact(message);

        // Then
        assertTrue(redacted.contains("***@***.***"),
            "邮箱应被掩码为 ***@***.***");
        assertFalse(redacted.contains("user@example.com"),
            "脱敏结果禁止包含原始邮箱");
    }

    @Test
    void testPhoneRedacted() {
        // Given
        String message = "Contact phone: (555) 123-4567";

        // When
        String redacted = piiRedactor.redact(message);

        // Then
        assertTrue(redacted.contains("(***) ***-****"),
            "电话应被掩码为 (***) ***-****");
        assertFalse(redacted.contains("(555) 123-4567"),
            "脱敏结果禁止包含原始电话号码");
    }

    @Test
    void testCreditCardRedacted() {
        // Given
        String message = "Payment card: 4532-1234-5678-9010";

        // When
        String redacted = piiRedactor.redact(message);

        // Then
        assertTrue(redacted.contains("****-****-****-****"),
            "信用卡号应被全量掩码");
        assertFalse(redacted.contains("4532-1234-5678-9010"),
            "脱敏结果禁止包含原始信用卡号");
    }

    @Test
    void testIPAddressRedacted() {
        // Given
        String message = "Request from IP: 192.168.1.100";

        // When
        String redacted = piiRedactor.redact(message);

        // Then
        assertTrue(redacted.contains("***.***.***.***"),
            "IP 地址应被掩码为 ***.***.***.***");
        assertFalse(redacted.contains("192.168.1.100"),
            "脱敏结果禁止包含原始 IP");
    }

    @Test
    void testMultiplePIITypesRedacted() {
        // Given: 包含多种 PII 类型的消息
        String message = "User SSN: 123-45-6789, Email: test@example.com, Phone: (555) 123-4567";

        // When: 执行脱敏
        String redacted = piiRedactor.redact(message);

        // Then: 验证所有类型都被脱敏
        assertTrue(redacted.contains("***-**-****"), "SSN 应被脱敏");
        assertTrue(redacted.contains("***@***.***"), "邮箱应被脱敏");
        assertTrue(redacted.contains("(***) ***-****"), "电话应被脱敏");

        assertFalse(redacted.contains("123-45-6789"), "原始 SSN 不应出现");
        assertFalse(redacted.contains("test@example.com"), "原始邮箱不应出现");
        assertFalse(redacted.contains("(555) 123-4567"), "原始电话不应出现");
    }
}
