package io.aster.policy.exception;

/**
 * 安全异常
 *
 * 当发生安全相关错误（如签名验证失败、未授权访问）时抛出
 */
public class SecurityException extends AsterPolicyException {

    private static final long serialVersionUID = 1L;

    /**
     * 安全事件类型
     */
    private final String eventType;

    /**
     * 租户 ID
     */
    private final String tenantId;

    public SecurityException(String message) {
        this(message, "SECURITY_ERROR", null);
    }

    public SecurityException(String message, String eventType) {
        this(message, eventType, null);
    }

    public SecurityException(String message, String eventType, String tenantId) {
        super(message, "SECURITY_" + eventType);
        this.eventType = eventType;
        this.tenantId = tenantId;
    }

    public SecurityException(String message, Throwable cause) {
        this(message, "SECURITY_ERROR", null, cause);
    }

    public SecurityException(String message, String eventType, String tenantId, Throwable cause) {
        super(message, "SECURITY_" + eventType, cause);
        this.eventType = eventType;
        this.tenantId = tenantId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTenantId() {
        return tenantId;
    }
}
