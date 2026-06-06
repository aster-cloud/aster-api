package io.aster.policy.rest.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 策略版本回滚请求
 *
 * 用于将策略回滚到指定的历史版本。
 *
 * @param targetVersion 目标版本号（timestamp）
 * @param reason        回滚原因（用于审计日志）
 */
public record RollbackRequest(
    @NotNull(message = "targetVersion 不能为空")
    Long targetVersion,

    // 写入审计日志的自由文本，限长防止超大 reason 撑爆审计/日志。
    @Size(max = CnlSourceLimits.MAX_FREETEXT_LENGTH, message = "reason 过长")
    String reason
) {
}
