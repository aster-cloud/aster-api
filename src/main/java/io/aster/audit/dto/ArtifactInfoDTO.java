package io.aster.audit.dto;

import java.time.Instant;

/**
 * 编译产物信息 DTO（Phase 3.2）
 *
 * 用于返回策略版本的编译产物追踪信息。
 */
public class ArtifactInfoDTO {

    /** 策略 ID */
    public String policyId;

    /** 策略版本号 */
    public Long version;

    /** 编译产物 SHA256 校验和 */
    public String artifactSha256;

    /** 编译产物存储路径 */
    public String artifactUri;

    /** Runtime 构建版本 */
    public String runtimeBuild;

    /** 创建时间 */
    public Instant createdAt;

    /**
     * 版本来源（G6）：manual / ai_draft / ai_draft_edited / imported。
     * 与 VersionHistoryDTO 一致——所有审计导出统一暴露来源，便于合规筛选。
     */
    public String sourceKind;
}
