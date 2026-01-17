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
}
