package io.aster.policy.rest.model;

import java.time.Instant;

/**
 * 策略版本信息
 *
 * 用于返回策略版本历史列表。
 */
public record PolicyVersionInfo(
    Long version,
    Boolean active,
    String moduleName,
    String functionName,
    Instant createdAt,
    String createdBy,
    String notes
) {
}
