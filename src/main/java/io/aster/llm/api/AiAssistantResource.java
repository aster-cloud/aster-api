package io.aster.llm.api;

import io.aster.llm.api.dto.CompleteRequest;
import io.aster.llm.api.dto.CompleteResponse;
import io.aster.llm.api.dto.ExplainRequest;
import io.aster.llm.api.dto.GeneratePolicyRequest;
import io.aster.llm.api.dto.SuggestRequest;
import io.aster.llm.config.LlmConfig;
import io.aster.llm.service.LlmProxyService;
import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * AI 助手 REST 端点
 *
 * 提供 NL-to-CNL 策略生成、策略解释、代码补全等 AI 能力。
 * 生成和解释使用 SSE 流式传输，补全使用同步请求。
 */
@Path("/api/v1/ai")
@RequireRole(Role.MEMBER)
public class AiAssistantResource {

    private static final Logger LOG = Logger.getLogger(AiAssistantResource.class);

    @Inject
    LlmProxyService llmProxyService;

    @Inject
    LlmConfig llmConfig;

    @Context
    RoutingContext routingContext;

    /**
     * NL-to-CNL 策略生成（SSE 流式）
     *
     * POST /api/v1/ai/generate
     *
     * 事件类型：
     * - delta: 增量内容
     * - validation_error: 编译校验失败
     * - final: 最终代码（已通过校验或达到最大重试次数）
     * - error: 服务端错误
     */
    @POST
    @Path("/generate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> generate(@Valid GeneratePolicyRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        LOG.infof("AI 生成请求: tenant=%s, locale=%s, goal=%.50s...",
            tenantId, request.getLocaleOrDefault(), request.goal());
        return llmProxyService.streamGenerate(tenantId, request);
    }

    /**
     * 策略/追踪解释（SSE 流式）
     *
     * POST /api/v1/ai/explain
     */
    @POST
    @Path("/explain")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> explain(@Valid ExplainRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        LOG.infof("AI 解释请求: tenant=%s, locale=%s", tenantId, request.getLocaleOrDefault());
        return llmProxyService.streamExplain(tenantId, request);
    }

    /**
     * 策略优化建议（SSE 流式）
     *
     * POST /api/v1/ai/suggest
     */
    @POST
    @Path("/suggest")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> suggest(@Valid SuggestRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        LOG.infof("AI 优化建议请求: tenant=%s, locale=%s", tenantId, request.getLocaleOrDefault());
        return llmProxyService.streamSuggest(tenantId, request);
    }

    /**
     * 代码补全（非流式，低延迟）
     *
     * POST /api/v1/ai/complete
     */
    @POST
    @Path("/complete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<CompleteResponse> complete(@Valid CompleteRequest request) {
        checkEnabled();
        String tenantId = tenantId();
        return llmProxyService.complete(tenantId, request);
    }

    private void checkEnabled() {
        if (!llmConfig.enabled()) {
            throw new WebApplicationException("AI 功能未启用", 503);
        }
    }

    private String tenantId() {
        if (routingContext == null || routingContext.request() == null) {
            return "default";
        }
        String tenant = routingContext.request().getHeader("X-Tenant-Id");
        return tenant == null || tenant.isBlank() ? "default" : tenant.trim();
    }
}
