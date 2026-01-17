package io.aster.policy.logging;

import io.aster.policy.common.PIIRedactor;
import io.aster.policy.config.PIIConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.Logger;

/**
 * Phase 0 Task 4.3 - PII 日志拦截器
 *
 * 拦截标记了 @NoPII 注解的方法，检测方法执行期间是否产生包含 PII 的日志。
 *
 * 工作机制：
 * 1. 检查 PII 保护是否启用（aster.pii.enforce=true）
 * 2. 方法执行前：记录当前日志上下文
 * 3. 方法执行中：允许正常记录日志
 * 4. 方法执行后：检查新产生的日志是否包含 PII
 *
 * 注意：
 * - 此拦截器采用渐进式启用策略，默认禁用（需显式 aster.pii.enforce=true 启用）
 * - 此实现依赖于 PIIRedactor 进行 PII 检测
 * - 当前实现为演示版本，实际生产环境需要集成日志框架的 Appender
 * - Task 4.4 的端到端测试会验证完整的日志检测功能
 *
 * 限制：
 * - JBoss Logging 不支持直接拦截日志输出
 * - 此拦截器主要用于方法参数和返回值的 PII 检测
 * - 实际日志 PII 防护由 PIIRedactingLogFilter 处理
 */
@NoPII
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class PIILoggingInterceptor {

    private static final Logger LOG = Logger.getLogger(PIILoggingInterceptor.class);
    private final PIIRedactor piiRedactor = new PIIRedactor();

    @Inject
    PIIConfig piiConfig;

    @AroundInvoke
    public Object intercept(InvocationContext context) throws Exception {
        // 检查 PII 保护是否启用（渐进式启用策略）
        if (!piiConfig.enforce()) {
            return context.proceed(); // PII 保护未启用，直接执行方法
        }

        String methodName = context.getMethod().getName();
        String className = context.getMethod().getDeclaringClass().getSimpleName();

        // 检查方法参数是否包含 PII
        Object[] parameters = context.getParameters();
        if (parameters != null) {
            for (int i = 0; i < parameters.length; i++) {
                Object param = parameters[i];
                if (param instanceof String) {
                    String paramStr = (String) param;
                    if (piiRedactor.containsPII(paramStr)) {
                        LOG.warnf("PII detected in method parameter: class=%s, method=%s, param_index=%d",
                            className, methodName, i);

                        // 脱敏参数（可选，用于演示）
                        // parameters[i] = piiRedactor.redact(paramStr);
                    }
                }
            }
        }

        // 执行原方法
        Object result = context.proceed();

        // 检查返回值是否包含 PII
        if (result instanceof String) {
            String resultStr = (String) result;
            if (piiRedactor.containsPII(resultStr)) {
                LOG.warnf("PII detected in method return value: class=%s, method=%s",
                    className, methodName);
            }
        }

        return result;
    }
}
