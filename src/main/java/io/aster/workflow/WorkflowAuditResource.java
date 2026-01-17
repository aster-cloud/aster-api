package io.aster.workflow;

import aster.runtime.workflow.WorkflowEvent;
import aster.runtime.workflow.WorkflowState;
import io.aster.workflow.dto.WorkflowEventDTO;
import io.aster.workflow.dto.WorkflowMetricsDTO;
import io.aster.workflow.dto.WorkflowStateDTO;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Workflow 审计和查询 REST API
 *
 * 提供 workflow 事件查询、状态查询和指标接口，满足审计和可观测性要求。
 */
@Path("/api/workflows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkflowAuditResource {

    @Inject
    PostgresEventStore eventStore;

    /**
     * 获取 workflow 的事件历史
     *
     * @param workflowId workflow 唯一标识符
     * @param fromSeq 起始序列号（可选，默认 0）
     * @return 事件列表
     */
    @GET
    @Path("/{workflowId}/events")
    public List<WorkflowEventDTO> getEvents(
            @PathParam("workflowId") String workflowId,
            @QueryParam("fromSeq") @DefaultValue("0") long fromSeq) {
        List<WorkflowEvent> events = eventStore.getEvents(workflowId, fromSeq);
        return events.stream()
                .map(this::toEventDTO)
                .collect(Collectors.toList());
    }

    /**
     * 获取 workflow 当前状态
     *
     * @param workflowId workflow 唯一标识符
     * @return workflow 状态
     */
    @GET
    @Path("/{workflowId}/state")
    public WorkflowStateDTO getState(@PathParam("workflowId") String workflowId) {
        Optional<WorkflowState> stateOpt = eventStore.getState(workflowId);
        if (stateOpt.isEmpty()) {
            throw new NotFoundException("Workflow not found: " + workflowId);
        }

        WorkflowState state = stateOpt.get();
        return toStateDTO(state);
    }

    /**
     * 获取所有 workflow 的聚合指标
     *
     * @return 指标统计
     */
    @GET
    @Path("/metrics")
    public WorkflowMetricsDTO getMetrics() {
        return new WorkflowMetricsDTO(
                WorkflowStateEntity.countByStatus("READY"),
                WorkflowStateEntity.countByStatus("RUNNING"),
                WorkflowStateEntity.countByStatus("COMPLETED"),
                WorkflowStateEntity.countByStatus("FAILED"),
                WorkflowStateEntity.countByStatus("COMPENSATING"),
                WorkflowStateEntity.countByStatus("COMPENSATED"),
                WorkflowStateEntity.countByStatus("COMPENSATION_FAILED")
        );
    }

    /**
     * 获取指定状态的 workflow 列表
     *
     * @param status 状态类型
     * @param limit 最大返回数量
     * @return workflow ID 列表
     */
    @GET
    @Path("/by-status/{status}")
    public List<String> getWorkflowsByStatus(
            @PathParam("status") String status,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return WorkflowStateEntity.findByStatus(status).stream()
                .limit(limit)
                .map(state -> state.workflowId.toString())
                .collect(Collectors.toList());
    }

    // ==================== 私有辅助方法 ====================

    private WorkflowEventDTO toEventDTO(WorkflowEvent event) {
        return new WorkflowEventDTO(
                event.getSequence(),
                event.getWorkflowId(),
                event.getEventType(),
                event.getPayload(),
                event.getOccurredAt()
        );
    }

    private WorkflowStateDTO toStateDTO(WorkflowState state) {
        return new WorkflowStateDTO(
                state.getWorkflowId(),
                state.getStatus().name(),
                state.getLastEventSeq(),
                state.getResult(),
                state.getSnapshot(),
                state.getSnapshotSeq(),
                state.getCreatedAt(),
                state.getUpdatedAt()
        );
    }
}
