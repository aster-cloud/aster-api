package io.aster.policy.logging;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase 0 Task 4.3 - @NoPII 注解
 *
 * 标记方法或类不应记录包含 PII 的日志。
 * 当此注解存在时，PIILoggingInterceptor 会拦截方法调用并检查日志输出。
 *
 * 使用方式：
 * 1. 标记在方法上：
 *    @NoPII
 *    public void processUserData(String ssn) { ... }
 *
 * 2. 标记在类上（对所有方法生效）：
 *    @NoPII
 *    public class UserService { ... }
 *
 * 工作机制：
 * - 拦截器在方法执行前后检查日志缓冲区
 * - 如果检测到 PII，记录警告日志并抛出异常（开发模式）
 * - 生产模式仅记录警告，不中断执行
 */
@InterceptorBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoPII {
}
