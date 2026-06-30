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
    String notes,
    // G6：版本来源（manual / ai_draft / ai_draft_edited / imported）升格为一等导出字段，
    // 不再仅存于 DB 列。合规消费者可据此筛选 AI 起草来源，无需解析 metadata。
    String sourceKind
) {
}
