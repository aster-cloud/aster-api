package io.aster.audit.service;

import io.aster.audit.dto.*;
import io.aster.common.dto.PagedResult;
import io.aster.policy.entity.PolicyVersion;
import io.aster.workflow.WorkflowStateEntity;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 策略审计服务（Phase 3.2）
 *
 * 提供审计查询功能：
 * - 版本使用报告
 * - 影响评估
 * - 编译产物追踪
 */
@ApplicationScoped
public class PolicyAuditService {

    /**
     * 获取使用特定策略版本的 workflow 列表
     *
     * 使用索引：idx_workflow_state_policy_version (policy_version_id, status)
     *
     * @param versionId 策略版本 ID
     * @param status    可选的状态过滤（RUNNING, COMPLETED, FAILED）
     * @param page      页码（从 0 开始）
     * @param size      每页大小
     * @return 分页的 workflow 使用信息
     */
    public PagedResult<WorkflowUsageDTO> getVersionUsage(Long versionId, String status, int page, int size) {
        String query = "policyVersionId = ?1";
        Object[] params;

        if (status != null && !status.isEmpty()) {
            query += " AND status = ?2";
            params = new Object[]{versionId, status};
        } else {
            params = new Object[]{versionId};
        }

        long total = WorkflowStateEntity.count(query, params);

        List<WorkflowStateEntity> states = WorkflowStateEntity
            .find(query + " ORDER BY policyActivatedAt DESC", params)
            .page(page, size)
            .list();

        List<WorkflowUsageDTO> items = states.stream()
            .map(s -> {
                WorkflowUsageDTO dto = new WorkflowUsageDTO();
                dto.workflowId = s.workflowId.toString();
                dto.status = s.status;
                dto.policyActivatedAt = s.policyActivatedAt;
                dto.lastEventSeq = s.lastEventSeq;
                dto.tenantId = s.tenantId;
                return dto;
            })
            .collect(Collectors.toList());

        return new PagedResult<>(items, total, page, size);
    }

    /**
     * 获取策略版本使用的时间线
     *
     * 使用索引：idx_workflow_state_policy_activated (policy_activated_at, policy_version_id)
     *
     * @param versionId 策略版本 ID
     * @param from      开始时间
     * @param to        结束时间
     * @param page      页码（从 0 开始）
     * @param size      每页大小
     * @return 分页的时间线事件
     */
    public PagedResult<TimelineEventDTO> getVersionTimeline(
        Long versionId,
        Instant from,
        Instant to,
        int page,
        int size
    ) {
        String query = "policyVersionId = ?1 AND policyActivatedAt BETWEEN ?2 AND ?3";
        long total = WorkflowStateEntity.count(query, versionId, from, to);

        List<WorkflowStateEntity> states = WorkflowStateEntity
            .find(query + " ORDER BY policyActivatedAt ASC", versionId, from, to)
            .page(page, size)
            .list();

        List<TimelineEventDTO> items = states.stream()
            .map(s -> {
                TimelineEventDTO dto = new TimelineEventDTO();
                dto.timestamp = s.policyActivatedAt;
                dto.eventType = "WorkflowActivated";
                dto.workflowId = s.workflowId.toString();
                dto.count = 1;
                return dto;
            })
            .collect(Collectors.toList());

        return new PagedResult<>(items, total, page, size);
    }

    /**
     * 评估策略版本回滚的影响
     *
     * 使用索引：idx_workflow_state_policy_version (policy_version_id, status)
     *
     * @param versionId 策略版本 ID
     * @return 影响评估结果
     */
    public ImpactAssessmentDTO assessImpact(Long versionId) {
        ImpactAssessmentDTO dto = new ImpactAssessmentDTO();
        dto.versionId = versionId;

        dto.activeCount = (int) WorkflowStateEntity.count(
            "policyVersionId = ?1 AND status = 'RUNNING'", versionId
        );
        dto.completedCount = (int) WorkflowStateEntity.count(
            "policyVersionId = ?1 AND status = 'COMPLETED'", versionId
        );
        dto.failedCount = (int) WorkflowStateEntity.count(
            "policyVersionId = ?1 AND status = 'FAILED'", versionId
        );
        dto.totalCount = dto.activeCount + dto.completedCount + dto.failedCount;

        // 风险评估逻辑
        if (dto.activeCount > 100) {
            dto.riskLevel = "HIGH";
        } else if (dto.activeCount > 10) {
            dto.riskLevel = "MEDIUM";
        } else {
            dto.riskLevel = "LOW";
        }

        return dto;
    }

    /**
     * 获取 workflow 的策略版本历史
     *
     * 使用主键索引查询单个 workflow
     *
     * @param workflowId Workflow ID
     * @return 版本历史列表
     */
    public List<VersionHistoryDTO> getWorkflowVersionHistory(UUID workflowId) {
        WorkflowStateEntity state = WorkflowStateEntity.findById(workflowId);

        if (state == null || state.policyVersionId == null) {
            return List.of();
        }

        // 查询策略版本信息
        PolicyVersion version = PolicyVersion.findById(state.policyVersionId);

        if (version == null) {
            return List.of();
        }

        VersionHistoryDTO dto = new VersionHistoryDTO();
        dto.versionId = version.id;
        dto.policyVersion = version.version;
        dto.activatedAt = state.policyActivatedAt;
        dto.deactivatedAt = null; // 当前版本仍在使用
        dto.durationMs = null; // 无停用时间，无法计算时长

        return List.of(dto);
    }

    /**
     * 根据 SHA256 查询编译产物信息
     *
     * 使用索引：idx_policy_versions_artifact_sha256
     *
     * @param sha256 编译产物 SHA256 校验和
     * @return 编译产物信息，不存在时返回 null
     */
    public ArtifactInfoDTO getArtifact(String sha256) {
        PolicyVersion version = PolicyVersion
            .find("artifactSha256", sha256)
            .firstResult();

        if (version == null) {
            return null;
        }

        ArtifactInfoDTO dto = new ArtifactInfoDTO();
        dto.policyId = version.policyId;
        dto.version = version.version;
        dto.artifactSha256 = version.artifactSha256;
        dto.artifactUri = version.artifactUri;
        dto.runtimeBuild = version.runtimeBuild;
        dto.createdAt = version.createdAt;

        return dto;
    }

    /**
     * 查询使用特定 runtime 版本的策略列表
     *
     * 使用索引：idx_policy_versions_runtime_build
     *
     * @param runtimeBuild Runtime 构建版本
     * @return 策略列表
     */
    public List<RuntimePolicyDTO> getRuntimePolicies(String runtimeBuild) {
        List<PolicyVersion> versions = PolicyVersion
            .find("runtimeBuild", runtimeBuild)
            .list();

        return versions.stream()
            .map(v -> {
                RuntimePolicyDTO dto = new RuntimePolicyDTO();
                dto.policyId = v.policyId;
                dto.version = v.version;
                dto.runtimeBuild = v.runtimeBuild;
                dto.activatedAt = v.activatedAt;
                return dto;
            })
            .collect(Collectors.toList());
    }
}
