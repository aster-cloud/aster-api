package io.aster.audit.dto;

import java.time.Instant;

/**
 * Runtime 策略 DTO（Phase 3.2）
 *
 * 用于返回使用特定 runtime 版本的策略信息。
 */
public class RuntimePolicyDTO {

    /** 策略 ID */
    public String policyId;

    /** 策略版本号 */
    public Long version;

    /** Runtime 构建版本 */
    public String runtimeBuild;

    /** 激活时间 */
    public Instant activatedAt;

    /**
     * 版本来源（G6）：manual / ai_draft / ai_draft_edited / imported。
     * 与 VersionHistoryDTO 一致——所有审计导出统一暴露来源，便于合规筛选。
     */
    public String sourceKind;
}
