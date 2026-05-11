package io.aster.policy.metrics.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * WAADR 视图单行（v1.2 加入 authorRole 维度）
 *
 * @param week       周起点（UTC 的周一 00:00）
 * @param tenantId   租户标识
 * @param authorRole 作者业务角色（business_expert / compliance_officer / risk_analyst）
 * @param waadr      该周该租户该角色被采纳的 AI 草稿规则数
 */
@RegisterForReflection
public record WaadrPoint(Instant week, String tenantId, String authorRole, long waadr) {
}
