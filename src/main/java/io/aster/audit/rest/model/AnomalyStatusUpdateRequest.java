package io.aster.audit.rest.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 异常状态更新请求（Phase 3.7）
 *
 * 用于手动更新异常状态，如标记为已解决或已忽略。
 *
 * @param status 新状态（PENDING, VERIFYING, VERIFIED, RESOLVED, DISMISSED）
 * @param notes  处置备注（用于审计和历史追溯）
 */
public record AnomalyStatusUpdateRequest(
    @NotNull(message = "status 不能为空")
    @Pattern(regexp = "PENDING|VERIFYING|VERIFIED|RESOLVED|DISMISSED",
             message = "status 必须是 PENDING, VERIFYING, VERIFIED, RESOLVED, DISMISSED 之一")
    String status,

    // 写入审计/历史的自由文本，限长 2KB（与 CnlSourceLimits.MAX_FREETEXT_LENGTH 对齐）。
    @Size(max = 2_048, message = "notes 过长（最多 2048 字符）")
    String notes
) {
}
