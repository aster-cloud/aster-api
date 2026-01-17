package io.aster.policy.common;

import java.util.regex.Pattern;

/**
 * PII 日志脱敏工具 - 自动识别并脱敏敏感信息
 * 支持：SSN（美国社会安全号）、Email、电话号码、信用卡号
 */
public class PIIRedactor {
    // 美国 SSN 格式: 123-45-6789 或 123456789
    private static final Pattern SSN_PATTERN = Pattern.compile(
        "\\b\\d{3}-?\\d{2}-?\\d{4}\\b"
    );

    // Email 格式: user@example.com
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"
    );

    // 电话号码格式: (123) 456-7890, 123-456-7890, 1234567890
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "\\(\\d{3}\\)\\s*\\d{3}[-.]?\\d{4}|\\b\\d{3}[-.]\\d{3}[-.]\\d{4}\\b|\\b\\d{10}\\b"
    );

    // 信用卡号格式: 1234-5678-9012-3456 或 1234567890123456 (13-19位)
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
        "\\b(?:\\d{4}[-\\s]?){3}\\d{4}(?:\\d{3})?\\b"
    );

    // IP 地址格式: 192.168.1.1
    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(
        "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"
    );

    private final boolean redactSSN;
    private final boolean redactEmail;
    private final boolean redactPhone;
    private final boolean redactCreditCard;
    private final boolean redactIPAddress;

    /**
     * 构造函数 - 默认启用所有 PII 脱敏
     */
    public PIIRedactor() {
        this(true, true, true, true, true);
    }

    /**
     * 构造函数 - 可配置脱敏选项
     */
    public PIIRedactor(boolean redactSSN, boolean redactEmail, boolean redactPhone,
                      boolean redactCreditCard, boolean redactIPAddress) {
        this.redactSSN = redactSSN;
        this.redactEmail = redactEmail;
        this.redactPhone = redactPhone;
        this.redactCreditCard = redactCreditCard;
        this.redactIPAddress = redactIPAddress;
    }

    /**
     * 脱敏日志消息中的所有 PII
     */
    public String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String result = message;

        if (redactSSN) {
            result = SSN_PATTERN.matcher(result).replaceAll("***-**-****");
        }

        if (redactEmail) {
            result = EMAIL_PATTERN.matcher(result).replaceAll("***@***.***");
        }

        if (redactPhone) {
            result = PHONE_PATTERN.matcher(result).replaceAll("(***) ***-****");
        }

        if (redactCreditCard) {
            result = CREDIT_CARD_PATTERN.matcher(result).replaceAll("****-****-****-****");
        }

        if (redactIPAddress) {
            result = IP_ADDRESS_PATTERN.matcher(result).replaceAll("***.***.***.***");
        }

        return result;
    }

    /**
     * 检查消息是否包含 PII（用于审计）
     */
    public boolean containsPII(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }

        return SSN_PATTERN.matcher(message).find()
            || EMAIL_PATTERN.matcher(message).find()
            || PHONE_PATTERN.matcher(message).find()
            || CREDIT_CARD_PATTERN.matcher(message).find()
            || IP_ADDRESS_PATTERN.matcher(message).find();
    }

    /**
     * 构建器模式用于创建可配置的 PIIRedactor
     */
    public static class Builder {
        private boolean redactSSN = true;
        private boolean redactEmail = true;
        private boolean redactPhone = true;
        private boolean redactCreditCard = true;
        private boolean redactIPAddress = true;

        public Builder redactSSN(boolean redact) {
            this.redactSSN = redact;
            return this;
        }

        public Builder redactEmail(boolean redact) {
            this.redactEmail = redact;
            return this;
        }

        public Builder redactPhone(boolean redact) {
            this.redactPhone = redact;
            return this;
        }

        public Builder redactCreditCard(boolean redact) {
            this.redactCreditCard = redact;
            return this;
        }

        public Builder redactIPAddress(boolean redact) {
            this.redactIPAddress = redact;
            return this;
        }

        public PIIRedactor build() {
            return new PIIRedactor(redactSSN, redactEmail, redactPhone,
                                  redactCreditCard, redactIPAddress);
        }
    }
}
