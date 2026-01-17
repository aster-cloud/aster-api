package io.aster.audit.dto;

import java.time.Instant;

/**
 * 版本历史 DTO（Phase 3.2）
 *
 * 用于返回 workflow 的策略版本历史记录。
 */
public class VersionHistoryDTO {

    /** 策略版本 ID（便于前端关联）*/
    public Long versionId;

    /** 策略版本号 */
    public Long policyVersion;

    /** 激活时间 */
    public Instant activatedAt;

    /** 停用时间 */
    public Instant deactivatedAt;

    /** 使用时长（毫秒）*/
    public Long durationMs;
}
