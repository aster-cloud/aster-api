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
}
