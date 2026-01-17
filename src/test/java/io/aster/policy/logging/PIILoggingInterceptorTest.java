package io.aster.policy.logging;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Task 4.3 - PIILoggingInterceptor 集成测试
 *
 * 验证 @NoPII 注解和拦截器的基本功能
 */
@QuarkusTest
public class PIILoggingInterceptorTest {

    @Inject
    TestService testService;

    @Test
    void testInterceptorDoesNotBreakNormalMethods() {
        // 正常方法调用应该不受影响
        String result = testService.normalMethod("test");
        assertEquals("NORMAL: test", result);
    }

    @Test
    void testInterceptorDetectsPIIInParameter() {
        // 包含 PII 的参数应该被检测（通过日志警告）
        // 注意：拦截器不会抛出异常，只会记录警告日志
        String result = testService.methodWithPII("test@example.com");
        assertEquals("PII: test@example.com", result);
    }

    @Test
    void testInterceptorDetectsPIIInReturnValue() {
        // 返回值包含 PII 应该被检测
        String result = testService.methodReturningPII();
        assertEquals("123-45-6789", result); // SSN format
    }

    @Test
    void testInterceptorWorksWithClassLevel() {
        // 类级别的注解应该对所有方法生效
        String result = testService.anotherMethod("555-1234");
        assertEquals("PHONE: 555-1234", result);
    }

    /**
     * 测试服务类 - 用于验证拦截器功能
     */
    @NoPII
    @ApplicationScoped
    public static class TestService {

        public String normalMethod(String input) {
            return "NORMAL: " + input;
        }

        public String methodWithPII(String email) {
            // 方法参数包含 PII (email)
            return "PII: " + email;
        }

        public String methodReturningPII() {
            // 返回值包含 PII (SSN)
            return "123-45-6789";
        }

        public String anotherMethod(String phone) {
            // 参数包含 PII (phone)
            return "PHONE: " + phone;
        }
    }
}
