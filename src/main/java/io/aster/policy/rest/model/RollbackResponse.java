package io.aster.policy.rest.model;

import java.time.Instant;

/**
 * 策略版本回滚响应
 *
 * 返回回滚操作的结果信息。
 */
public record RollbackResponse(
    boolean success,
    String message,
    Long activeVersion,
    Instant rolledBackAt
) {
    public static RollbackResponse success(Long activeVersion) {
        return new RollbackResponse(
            true,
            "策略已成功回滚到版本 " + activeVersion,
            activeVersion,
            Instant.now()
        );
    }

    public static RollbackResponse failure(String errorMessage) {
        return new RollbackResponse(
            false,
            errorMessage,
            null,
            null
        );
    }
}
