package io.aster.policy.logging;

import io.aster.policy.common.PIIRedactor;

import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * JBoss Logging 过滤器 - 自动脱敏日志消息中的 PII
 *
 * 在日志输出前自动检测并脱敏以下敏感信息：
 * - SSN（美国社会安全号）
 * - Email 地址
 * - 电话号码
 * - 信用卡号
 * - IP 地址
 *
 * 使用方法：在 application.properties 中配置
 * quarkus.log.filter."io.aster.policy.logging.PIIRedactingLogFilter".enable=true
 */
public class PIIRedactingLogFilter implements Filter {

    private final PIIRedactor piiRedactor = new PIIRedactor();

    @Override
    public boolean isLoggable(LogRecord record) {
        // 对日志消息进行 PII 脱敏
        if (record != null && record.getMessage() != null) {
            String originalMessage = record.getMessage();
            String redactedMessage = piiRedactor.redact(originalMessage);
            record.setMessage(redactedMessage);
        }
        return true; // 始终允许记录日志
    }
}
