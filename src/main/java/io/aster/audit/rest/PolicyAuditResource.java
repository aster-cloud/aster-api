package io.aster.audit.rest;

import io.aster.audit.dto.*;
import io.aster.audit.service.PolicyAuditService;
import io.aster.common.dto.PagedResult;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 策略审计 REST 控制器（Phase 3.2）
 *
 * 提供审计查询 API：
 * - 版本使用报告
 * - 影响评估
 * - 编译产物追踪
 */
@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
public class PolicyAuditResource {

    @Inject
    PolicyAuditService auditService;

    /**
     * 获取使用特定策略版本的 workflow 列表
     *
     * GET /api/audit/policy-versions/{versionId}/usage?status=RUNNING&page=0&size=20
     *
     * @param versionId 策略版本 ID
     * @param status    可选的状态过滤（RUNNING, COMPLETED, FAILED）
     * @param page      页码（从 0 开始，默认 0）
     * @param size      每页大小（默认 20，最大 100）
     * @return 分页的 workflow 使用信息
     */
    @GET
    @Path("/policy-versions/{versionId}/usage")
    @Blocking
    public PagedResult<WorkflowUsageDTO> getVersionUsage(
        @PathParam("versionId") Long versionId,
        @QueryParam("status") String status,
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("20") int size
    ) {
        // 参数验证
        if (page < 0 || size <= 0 || size > 100) {
            throw new BadRequestException("Invalid pagination parameters: page must be >= 0, size must be > 0 and <= 100");
        }

        return auditService.getVersionUsage(versionId, status, page, size);
    }

    /**
     * 获取策略版本使用的时间线
     *
     * GET /api/audit/policy-versions/{versionId}/timeline?from=2025-01-01T00:00:00Z&to=2025-12-31T23:59:59Z&page=0&size=20
     *
     * @param versionId 策略版本 ID
     * @param from      开始时间（ISO-8601 格式）
     * @param to        结束时间（ISO-8601 格式）
     * @param page      页码（从 0 开始，默认 0）
     * @param size      每页大小（默认 20，最大 100）
     * @return 分页的时间线事件
     */
    @GET
    @Path("/policy-versions/{versionId}/timeline")
    @Blocking
    public PagedResult<TimelineEventDTO> getVersionTimeline(
        @PathParam("versionId") Long versionId,
        @QueryParam("from") Instant from,
        @QueryParam("to") Instant to,
        @QueryParam("page") @DefaultValue("0") int page,
        @QueryParam("size") @DefaultValue("20") int size
    ) {
        // 参数验证
        if (page < 0 || size <= 0 || size > 100) {
            throw new BadRequestException("Invalid pagination parameters: page must be >= 0, size must be > 0 and <= 100");
        }

        if (from == null || to == null) {
            throw new BadRequestException("Both 'from' and 'to' parameters are required");
        }

        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must be before or equal to 'to'");
        }

        return auditService.getVersionTimeline(versionId, from, to, page, size);
    }

    /**
     * 评估策略版本回滚的影响
     *
     * GET /api/audit/policy-versions/{versionId}/impact
     *
     * @param versionId 策略版本 ID
     * @return 影响评估结果（含风险等级）
     */
    @GET
    @Path("/policy-versions/{versionId}/impact")
    @Blocking
    public ImpactAssessmentDTO assessImpact(@PathParam("versionId") Long versionId) {
        ImpactAssessmentDTO result = auditService.assessImpact(versionId);

        if (result.totalCount == 0) {
            throw new NotFoundException("Policy version not found or not used: " + versionId);
        }

        return result;
    }

    /**
     * 获取 workflow 的策略版本历史
     *
     * GET /api/audit/workflows/{workflowId}/version-history
     *
     * @param workflowId Workflow ID（UUID 格式）
     * @return 版本历史列表
     */
    @GET
    @Path("/workflows/{workflowId}/version-history")
    @Blocking
    public List<VersionHistoryDTO> getWorkflowVersionHistory(@PathParam("workflowId") String workflowId) {
        try {
            UUID uuid = UUID.fromString(workflowId);
            return auditService.getWorkflowVersionHistory(uuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid workflow ID format: must be a valid UUID");
        }
    }

    /**
     * 根据 SHA256 查询编译产物信息
     *
     * GET /api/audit/artifacts/{sha256}
     *
     * @param sha256 编译产物 SHA256 校验和（64 位十六进制字符串）
     * @return 编译产物信息
     */
    @GET
    @Path("/artifacts/{sha256}")
    @Blocking
    public ArtifactInfoDTO getArtifact(@PathParam("sha256") String sha256) {
        // 参数验证：SHA256 应该是 64 位十六进制字符串
        if (sha256 == null || !sha256.matches("^[a-fA-F0-9]{64}$")) {
            throw new BadRequestException("Invalid SHA256 format: must be 64 hexadecimal characters");
        }

        ArtifactInfoDTO result = auditService.getArtifact(sha256);

        if (result == null) {
            throw new NotFoundException("Artifact not found: " + sha256);
        }

        return result;
    }

    /**
     * 查询使用特定 runtime 版本的策略列表
     *
     * GET /api/audit/runtime/{build}/policies
     *
     * @param build Runtime 构建版本（如 "1.0.0"）
     * @return 策略列表
     */
    @GET
    @Path("/runtime/{build}/policies")
    @Blocking
    public List<RuntimePolicyDTO> getRuntimePolicies(@PathParam("build") String build) {
        if (build == null || build.trim().isEmpty()) {
            throw new BadRequestException("Runtime build parameter cannot be empty");
        }

        return auditService.getRuntimePolicies(build);
    }
}
