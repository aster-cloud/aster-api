package io.aster.policy.rest;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块目录（ADR 0015 阶段3d）。
 *
 * <p>列出当前租户内可作为 library 被 {@code Use} 引用的已发布模块及其版本，
 * 供编辑器（Monaco）做 {@code Use} 补全、版本提示与 hover。
 *
 * <p>安全：严格 tenant 隔离——只返回当前租户的 library 模块，不泄露其它租户。
 */
@Path("/api/v1/modules/catalog")
@Produces(MediaType.APPLICATION_JSON)
public class ModuleCatalogResource {

    private static final Logger LOG = Logger.getLogger(ModuleCatalogResource.class);

    @Inject
    TenantContext tenantContext;

    @GET
    public Response list() {
        String tenantId = tenantContext == null ? null : tenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "tenant context required"))
                .build();
        }

        // 按模块名聚合版本（findLibraryCatalog 已按 moduleName asc, version desc 排序）
        Map<String, ModuleEntry> byModule = new LinkedHashMap<>();
        for (PolicyVersion v : PolicyVersion.findLibraryCatalog(tenantId)) {
            ModuleEntry entry = byModule.computeIfAbsent(
                v.moduleName, name -> new ModuleEntry(name, v.functionName, new ArrayList<>()));
            entry.versions().add(new VersionEntry(v.version, v.createdAt == null ? null : v.createdAt.toString()));
        }

        LOG.debugf("Module catalog for tenant %s: %d modules", tenantId, byModule.size());
        return Response.ok(new CatalogResponse(List.copyOf(byModule.values())))
            .header("Cache-Control", "no-store")
            .build();
    }

    /** 目录响应：可引用模块列表。 */
    public record CatalogResponse(List<ModuleEntry> modules) {}

    /** 单个模块及其全部可引用版本。 */
    public record ModuleEntry(String moduleName, String functionName, List<VersionEntry> versions) {}

    /** 单个版本。 */
    public record VersionEntry(Long version, String publishedAt) {}
}
