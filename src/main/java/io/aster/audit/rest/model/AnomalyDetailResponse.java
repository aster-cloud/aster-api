package io.aster.audit.rest.model;

import java.time.Instant;

/**
 * 异常详情响应（Phase 3.7）
 *
 * 包含异常的完整信息，包括状态、验证结果等。
 *
 * @param id                 异常 ID
 * @param type               异常类型（HIGH_FAILURE_RATE, ZOMBIE_VERSION, PERFORMANCE_DEGRADATION）
 * @param severity           严重程度（CRITICAL, WARNING, INFO）
 * @param status             异常状态（PENDING, VERIFYING, VERIFIED, RESOLVED, DISMISSED）
 * @param description        异常描述
 * @param recommendation     建议措施
 * @param verificationResult Replay 验证结果 JSON 字符串
 * @param detectedAt         检测时间
 * @param resolvedAt         解决时间
 */
public record AnomalyDetailResponse(
    Long id,
    String type,
    String severity,
    String status,
    String description,
    String recommendation,
    String verificationResult,
    Instant detectedAt,
    Instant resolvedAt
) {
}
