package io.aster.policy.exception;

/**
 * Aster Policy API 异常基类
 *
 * 所有业务异常都应继承此类，便于全局异常处理和错误码管理
 */
public class AsterPolicyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码（用于客户端识别错误类型）
     */
    private final String errorCode;

    /**
     * 是否可重试
     */
    private final boolean retryable;

    public AsterPolicyException(String message) {
        this(message, "ASTER_ERROR", false);
    }

    public AsterPolicyException(String message, String errorCode) {
        this(message, errorCode, false);
    }

    public AsterPolicyException(String message, String errorCode, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public AsterPolicyException(String message, Throwable cause) {
        this(message, "ASTER_ERROR", false, cause);
    }

    public AsterPolicyException(String message, String errorCode, Throwable cause) {
        this(message, errorCode, false, cause);
    }

    public AsterPolicyException(String message, String errorCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
