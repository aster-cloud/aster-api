package io.aster.policy.metrics;

import io.aster.policy.metrics.dto.WaadrPoint;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * WAADR 北极星指标查询端点
 *
 * 默认仅返回当前 X-Tenant-Id 的数据；传 tenant=* 可跨租户聚合（仅 PLATFORM_ADMIN，
 * 当前实现为 ADMIN 角色 + 显式参数，后续如有 PLATFORM_ADMIN 角色可收紧）。
 */
@Path("/api/v1/metrics/waadr")
@Produces(MediaType.APPLICATION_JSON)
@RequireRole(Role.ADMIN)
public class WaadrMetricsResource {

    @Inject
    WaadrMetricsService service;

    @Context
    RoutingContext routingContext;

    @GET
    @Blocking
    public List<WaadrPoint> getWeeklyWaadr(
        @QueryParam("weeks") @DefaultValue("12") int weeks,
        @QueryParam("tenant") String tenantParam
    ) {
        int safeWeeks = Math.max(1, Math.min(weeks, 52));
        String requestTenantId = currentTenantId();

        // tenant=* 表示跨租户聚合，传 null 给 service 即可
        String filterTenantId = "*".equals(tenantParam) ? null : requestTenantId;
        return service.fetchWeeklyWaadr(filterTenantId, safeWeeks);
    }

    private String currentTenantId() {
        if (routingContext == null || routingContext.request() == null) {
            return "default";
        }
        String tenant = routingContext.request().getHeader("X-Tenant-Id");
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim();
    }
}
