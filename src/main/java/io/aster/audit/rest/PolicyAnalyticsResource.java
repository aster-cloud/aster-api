package io.aster.audit.rest;

import io.aster.audit.dto.*;
import io.aster.audit.entity.AnomalyReportEntity;
import io.aster.audit.inbox.InboxGuard;
import io.aster.audit.rest.model.AnomalyActionResponse;
import io.aster.audit.rest.model.AnomalyDetailResponse;
import io.aster.audit.rest.model.AnomalyStatusUpdateRequest;
import io.aster.audit.service.AnomalyWorkflowService;
import io.aster.audit.service.PolicyAnalyticsService;
import io.aster.policy.tenant.TenantContext;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 策略分析 REST 控制器（Phase 3.3, Phase 3.7）
 *
 * 提供实时审计仪表板 API：
 * - 聚合统计（按时间粒度聚合版本使用情况）
 * - 异常检测（高失败率、僵尸版本）
 * - 版本对比（对比两个版本的性能指标）
 *
 * Phase 3.7 扩展 - 异常管理 API：
 * - GET /api/audit/anomalies/{id} - 获取异常详情（含验证结果）
 * - POST /api/audit/anomalies/{id}/actions/verify - 手动触发验证
 * - PATCH /api/audit/anomalies/{id}/status - 更新异常状态
 */
@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
public class PolicyAnalyticsResource {

    @Inject
    PolicyAnalyticsService analyticsService;

    @Inject
    AnomalyWorkflowService workflowService;

    @Inject
    InboxGuard inboxGuard;

    @Inject
    TenantContext tenantContext;

    /**
     * 获取版本使用统计（按时间粒度聚合）
     *
     * GET /api/audit/stats/version-usage?versionId=1&granularity=day&from=2025-01-01T00:00:00Z&to=2025-01-31T23:59:59Z
     *
     * Phase 4.3: 租户ID 从 X-Tenant-Id header 自动获取（由 TenantFilter 强制验证）
     *
     * @param versionId   策略版本 ID（必填）
     * @param granularity 时间粒度（hour, day, week, month，默认 day）
     * @param from        开始时间（默认最近 7 天）
     * @param to          结束时间（默认当前时间）
     * @return 按时间桶聚合的统计数据
     */
    @GET
    @Path("/stats/version-usage")
    @Blocking
    public List<VersionUsageStatsDTO> getVersionUsageStats(
        @QueryParam("versionId") Long versionId,
        @QueryParam("granularity") @DefaultValue("day") String granularity,
        @QueryParam("from") Instant from,
        @QueryParam("to") Instant to
    ) {
        // 参数验证
        if (versionId == null) {
            throw new BadRequestException("versionId parameter is required");
        }

        if (!List.of("hour", "day", "week", "month").contains(granularity)) {
            throw new BadRequestException("Invalid granularity: must be one of [hour, day, week, month]");
        }

        // 默认时间范围：最近 7 天
        if (from == null) {
            from = Instant.now().minus(7, ChronoUnit.DAYS);
        }
        if (to == null) {
            to = Instant.now();
        }

        // 验证时间顺序
        if (from.isAfter(to)) {
            throw new BadRequestException("'from' must be before or equal to 'to'");
        }

        // 限制最大时间跨度
        long daysDiff = Duration.between(from, to).toDays();
        if (daysDiff > 90) {
            throw new BadRequestException("Time range cannot exceed 90 days (current: " + daysDiff + " days)");
        }

        // Phase 4.3: TenantContext 由 TenantFilter 自动填充，Service 层自动使用
        return analyticsService.getVersionUsageStats(versionId, granularity, from, to);
    }

    /**
     * 获取异常检测报告（异步模式 - Phase 3.4, Phase 4.3）
     *
     * GET /api/audit/anomalies?page=1&size=20&type=HIGH_FAILURE_RATE&days=7
     *
     * 从 anomaly_reports 表查询异常记录，而非实时计算。
     * 异常数据由定时任务（AnomalyDetectionScheduler）每小时生成。
     *
     * Phase 4.3: 租户ID 从 X-Tenant-Id header 自动获取（由 TenantFilter 强制验证）
     *
     * @param page 页码（从 1 开始，默认 1）
     * @param size 每页大小（1-100，默认 20）
     * @param type 异常类型过滤（HIGH_FAILURE_RATE, ZOMBIE_VERSION, PERFORMANCE_DEGRADATION，可选）
     * @param days 时间窗口（天，默认 30 天）
     * @return 异常报告列表
     */
    @GET
    @Path("/anomalies")
    @Blocking
    public List<AnomalyReportDTO> detectAnomalies(
        @QueryParam("page") @DefaultValue("1") int page,
        @QueryParam("size") @DefaultValue("20") int size,
        @QueryParam("type") String type,
        @QueryParam("days") @DefaultValue("30") int days
    ) {
        // 参数验证
        if (page < 1) {
            throw new BadRequestException("page must be >= 1");
        }
        if (size < 1 || size > 100) {
            throw new BadRequestException("size must be between 1 and 100");
        }
        if (days < 1 || days > 365) {
            throw new BadRequestException("days must be between 1 and 365");
        }

        // Phase 4.3: 强制租户过滤，从 TenantContext 自动获取
        String tenantId = tenantContext.getCurrentTenant();

        // 构建查询条件
        String query = "tenantId = ?1 AND detectedAt >= ?2";
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(Instant.now().minus(days, ChronoUnit.DAYS));

        // 可选的异常类型过滤
        if (type != null && !type.isBlank()) {
            query += " AND anomalyType = ?3";
            params.add(type);
        }

        // 执行分页查询
        PanacheQuery<AnomalyReportEntity> panacheQuery = AnomalyReportEntity.find(
            query + " ORDER BY detectedAt DESC",
            params.toArray()
        );

        List<AnomalyReportEntity> entities = panacheQuery
            .page(page - 1, size)  // Panache 使用 0-based 索引
            .list();

        // 转换为 DTO
        return entities.stream()
            .map(this::toAnomalyReportDTO)
            .collect(Collectors.toList());
    }

    /**
     * 将 AnomalyReportEntity 转换为 AnomalyReportDTO
     */
    private AnomalyReportDTO toAnomalyReportDTO(AnomalyReportEntity entity) {
        AnomalyReportDTO dto = new AnomalyReportDTO();
        dto.anomalyType = entity.anomalyType;
        dto.versionId = entity.versionId;
        dto.policyId = entity.policyId;
        dto.severity = entity.severity;
        dto.description = entity.description;
        dto.metricValue = entity.metricValue;
        dto.threshold = entity.threshold;
        dto.detectedAt = entity.detectedAt;
        dto.recommendation = entity.recommendation;
        return dto;
    }

    /**
     * 对比两个版本的性能指标
     *
     * GET /api/audit/compare?versionA=1&versionB=2&days=7
     *
     * @param versionAId 版本 A ID（必填）
     * @param versionBId 版本 B ID（必填）
     * @param days       对比时间窗口（天，默认 7 天）
     * @return 版本对比结果
     */
    @GET
    @Path("/compare")
    @Blocking
    public VersionComparisonDTO compareVersions(
        @QueryParam("versionA") Long versionAId,
        @QueryParam("versionB") Long versionBId,
        @QueryParam("days") @DefaultValue("7") int days
    ) {
        // 参数验证
        if (versionAId == null || versionBId == null) {
            throw new BadRequestException("Both versionA and versionB parameters are required");
        }

        if (versionAId.equals(versionBId)) {
            throw new BadRequestException("versionA and versionB must be different");
        }

        if (days < 1 || days > 90) {
            throw new BadRequestException("days must be between 1 and 90");
        }

        return analyticsService.compareVersions(versionAId, versionBId, days);
    }

    // ==================== Phase 3.7: 异常管理 API ====================

    /**
     * 获取异常详情（含验证结果）
     *
     * GET /api/audit/anomalies/{id}
     *
     * Phase 4.3: 强制租户过滤，防止跨租户数据访问
     *
     * @param id 异常报告 ID
     * @return 异常详情，包含验证结果和处置状态
     */
    @GET
    @Path("/anomalies/{id}")
    @Blocking
    public Response getAnomalyDetail(@PathParam("id") Long id) {
        // Phase 4.3: 强制租户过滤，从 TenantContext 自动获取
        String tenantId = tenantContext.getCurrentTenant();

        // 查询异常实体（包含租户验证）
        AnomalyReportEntity entity = AnomalyReportEntity.find(
            "id = ?1 AND tenantId = ?2",
            id,
            tenantId
        ).firstResult();

        if (entity == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("Anomaly not found: id=" + id)
                .build();
        }

        // 映射到 DetailResponse
        AnomalyDetailResponse response = new AnomalyDetailResponse(
            entity.id,
            entity.anomalyType,
            entity.severity,
            entity.status,
            entity.description,
            entity.recommendation,
            entity.verificationResult,
            entity.detectedAt,
            entity.resolvedAt
        );

        return Response.ok(response).build();
    }

    /**
     * 手动触发异常验证
     *
     * POST /api/audit/anomalies/{id}/actions/verify
     *
     * 提交验证动作到队列，由 AnomalyActionScheduler 异步执行。
     *
     * Phase 4.3: 强制租户验证，防止跨租户触发验证
     *
     * @param id 异常报告 ID
     * @return 验证动作响应（含动作 ID）
     */
    @POST
    @Path("/anomalies/{id}/actions/verify")
    public Uni<Response> triggerVerification(@PathParam("id") Long id) {
        // Phase 4.3: 强制租户验证
        String tenantId = tenantContext.getCurrentTenant();

        // 先验证实体是否存在且属于当前租户
        AnomalyReportEntity entity = AnomalyReportEntity.find(
            "id = ?1 AND tenantId = ?2",
            id,
            tenantId
        ).firstResult();

        if (entity == null) {
            return Uni.createFrom().item(
                Response.status(Response.Status.NOT_FOUND)
                    .entity("Anomaly not found: id=" + id)
                    .build()
            );
        }

        // 租户验证通过，提交验证动作
        return workflowService.submitVerificationAction(id)
            .onItem().transform(actionId -> {
                AnomalyActionResponse response = new AnomalyActionResponse(
                    actionId,
                    "VERIFY_REPLAY",
                    "验证动作已提交到队列"
                );
                return Response.accepted(response).build();
            })
            .onFailure().recoverWithItem(failure -> {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(failure.getMessage())
                    .build();
            });
    }

    /**
     * 更新异常状态
     *
     * PATCH /api/audit/anomalies/{id}/status
     *
     * 支持的状态转换：
     * - PENDING → DISMISSED
     * - VERIFIED → RESOLVED
     * - VERIFIED → DISMISSED
     *
     * @param id      异常报告 ID
     * @param request 状态更新请求（包含新状态和备注）
     * @return 204 No Content（成功）或 400/404（失败）
     */
    @PATCH
    @Path("/anomalies/{id}/status")
    @Blocking
    public Uni<Response> updateAnomalyStatus(
        @PathParam("id") Long id,
        @HeaderParam("Idempotency-Key") String idempotencyKey,
        @HeaderParam("X-Tenant-Id") String tenantId,
        @Valid AnomalyStatusUpdateRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return performUpdateAnomalyStatus(id, request);
        }
        boolean acquired = inboxGuard.tryAcquireBlocking(idempotencyKey, "UPDATE_ANOMALY_STATUS", tenantId);
        if (!acquired) {
            return Uni.createFrom().item(
                Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                        "error", "Duplicate request",
                        "idempotencyKey", idempotencyKey
                    ))
                    .build()
            );
        }
        return performUpdateAnomalyStatus(id, request);
    }

    private Uni<Response> performUpdateAnomalyStatus(Long id, AnomalyStatusUpdateRequest request) {
        // Phase 4.3: 强制租户验证，防止跨租户修改
        String tenantId = tenantContext.getCurrentTenant();

        // 先验证实体是否存在且属于当前租户
        AnomalyReportEntity entity = AnomalyReportEntity.find(
            "id = ?1 AND tenantId = ?2",
            id,
            tenantId
        ).firstResult();

        if (entity == null) {
            return Uni.createFrom().item(
                Response.status(Response.Status.NOT_FOUND)
                    .entity("Anomaly not found: id=" + id)
                    .build()
            );
        }

        // 租户验证通过，执行状态更新
        return workflowService.updateStatus(id, request.status(), request.notes())
            .onItem().transform(success -> Response.noContent().build())
            .onFailure().recoverWithItem(failure -> {
                return Response.status(Response.Status.BAD_REQUEST)
                    .entity(failure.getMessage())
                    .build();
            });
    }
}
