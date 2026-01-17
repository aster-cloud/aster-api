package io.aster.policy.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 验证异常
 *
 * 当请求参数验证失败时抛出
 */
public class ValidationException extends AsterPolicyException {

    private static final long serialVersionUID = 1L;

    /**
     * 验证错误列表（使用 ArrayList 以支持序列化）
     */
    private final ArrayList<String> validationErrors;

    public ValidationException(String message) {
        this(message, Collections.emptyList());
    }

    public ValidationException(String message, List<String> validationErrors) {
        super(message, "VALIDATION_ERROR");
        this.validationErrors = validationErrors != null
            ? new ArrayList<>(validationErrors)
            : new ArrayList<>();
    }

    public ValidationException(String message, String fieldName) {
        super(message, "VALIDATION_ERROR");
        this.validationErrors = new ArrayList<>();
        this.validationErrors.add(fieldName + ": " + message);
    }

    public List<String> getValidationErrors() {
        return Collections.unmodifiableList(validationErrors);
    }
}
