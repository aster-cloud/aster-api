package io.aster.audit.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.UUID;

/**
 * 异常动作 payload
 *
 * workflowId：Replay 验证使用
 * targetVersion：自动回滚使用
 */
@RegisterForReflection
public record AnomalyActionPayload(
    UUID workflowId,
    Long targetVersion
) {
}
