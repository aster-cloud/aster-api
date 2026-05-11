package io.aster.policy.rest;

import io.aster.policy.telemetry.NsmEvents;
import io.aster.policy.telemetry.NsmTelemetry;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Staging 测试专用端点：手工触发 NSM 事件
 *
 * <h2>为什么名字保留 Staging</h2>
 * 此端点**不是通用 telemetry 接口**，是 staging/dev 测试设施。它发出的事件含合成 ID
 * （staging-user-N / staging-rule-N）和 emitted_by="staging" 标记，与生产事件契约不兼容。
 * 生产环境启用会污染 Mixpanel / Counter 数据。命名 + URL path + 默认禁用 + 生产显式禁用 = 多层护栏。
 *
 * <h2>用途</h2>
 *   - staging 端到端测试时填充 Grafana 反指标面板（Counter 数据）
 *   - 默认禁用；通过 aster.staging.telemetry.enabled=true 启用
 *   - 启动时若被启用，打印警告日志便于运维识别
 *
 * <h2>启用时的告警事件属性</h2>
 *   - distinct_id = "staging-user-N"
 *   - rule_id     = "staging-rule-N"
 *   - emitted_by  = "staging"
 *
 * <h2>关联</h2>
 *   - aster-deploy/docs/pm/03-telemetry-spec.md（事件契约）
 *   - aster-api/src/main/java/io/aster/policy/telemetry/NsmTelemetry.java
 */
@Path("/api/internal/staging/telemetry")
public class StagingTelemetryResource {

    private static final Logger LOG = Logger.getLogger(StagingTelemetryResource.class);

    @Inject
    NsmTelemetry nsmTelemetry;

    @ConfigProperty(name = "aster.staging.telemetry.enabled", defaultValue = "false")
    boolean enabled;

    /**
     * 启动期检查：被启用时打印一行醒目警告
     * 避免 K3S 误传 ASTER_STAGING_TELEMETRY_ENABLED=true 进生产
     */
    void onStart(@Observes StartupEvent ev) {
        if (enabled) {
            LOG.warnf("⚠ StagingTelemetryResource 已启用 — 仅 staging/dev 应使用，生产配置错误！" +
                "事件含合成 ID 与 emitted_by=\"staging\"，将污染 NSM 数据。");
        }
    }

    @POST
    @Path("/draft-published/{tenantId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response emitDraftPublished(
        @PathParam("tenantId") String tenantId,
        @QueryParam("count") Integer count,
        @QueryParam("source_kind") String sourceKind,
        @QueryParam("author_role") String authorRole
    ) {
        if (!enabled) {
            return Response.status(404).build();
        }
        int n = count != null && count > 0 ? Math.min(count, 1000) : 1;
        String sk = sourceKind != null ? sourceKind : "ai_draft_edited";
        String ar = authorRole != null ? authorRole : "business_expert";
        for (int i = 0; i < n; i++) {
            nsmTelemetry.track(
                "staging-user-" + i,
                NsmEvents.DRAFT_PUBLISHED,
                Map.of(
                    "rule_id", "staging-rule-" + i,
                    "version", System.currentTimeMillis(),
                    "source_kind", sk,
                    "author_role", ar,
                    "tenant_id", tenantId,
                    "emitted_by", "staging"
                )
            );
        }
        return Response.ok(Map.of("emitted", n, "tenant", tenantId, "author_role", ar)).build();
    }

    @POST
    @Path("/rule-rolled-back/{tenantId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Blocking
    public Response emitRolledBack(
        @PathParam("tenantId") String tenantId,
        @QueryParam("count") Integer count
    ) {
        if (!enabled) {
            return Response.status(404).build();
        }
        int n = count != null && count > 0 ? Math.min(count, 100) : 1;
        for (int i = 0; i < n; i++) {
            nsmTelemetry.track(
                "staging-user",
                NsmEvents.RULE_ROLLED_BACK,
                Map.of(
                    "rule_id", "staging-rule-" + i,
                    "from_version", System.currentTimeMillis(),
                    "to_version", System.currentTimeMillis() - 1000,
                    "tenant_id", tenantId,
                    "reason", "staging-rollback-test"
                )
            );
        }
        return Response.ok(Map.of("emitted", n, "tenant", tenantId)).build();
    }
}
