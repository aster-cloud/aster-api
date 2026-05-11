package io.aster.policy.security;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.Priorities;
import java.io.ByteArrayInputStream;

/**
 * 请求签名验证过滤器
 *
 * 拦截所有请求，验证 HMAC 签名和 Nonce，防止请求篡改和重放攻击
 *
 * 使用 Quarkus RESTEasy Reactive 原生 @ServerRequestFilter 注解，
 * 返回 Uni<Void> 以支持异步读取请求体。
 */
@ApplicationScoped
public class RequestSignatureFilter {

    @Inject
    HmacSignatureService hmacSignatureService;

    @Inject
    NonceService nonceService;

    @ConfigProperty(name = "aster.security.signature.enabled", defaultValue = "true")
    boolean signatureEnabled;

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION)
    public Uni<Void> filter(ContainerRequestContext ctx) {
        // 跳过健康检查和内部端点（支持带/不带前导斜杠的路径）
        // /api/internal/* 是跨服务接口，自带 HMAC 验签，不走全局签名过滤器
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.startsWith("/internal/") || path.startsWith("internal/")
                || path.startsWith("/api/internal/") || path.startsWith("api/internal/")) {
            return Uni.createFrom().voidItem();
        }

        // 如果签名验证未启用，跳过
        if (!signatureEnabled) {
            return Uni.createFrom().voidItem();
        }

        // 使用 Mutiny 异步读取请求体
        return Uni.createFrom().item(() -> {
            try {
                // 读取请求体（只能读取一次，需要缓存）
                byte[] body = ctx.getEntityStream().readAllBytes();
                ctx.setEntityStream(new ByteArrayInputStream(body));

                // 验证签名
                hmacSignatureService.verify(ctx, body);

                // 验证 Nonce（防重放）
                String tenantId = ctx.getHeaderString("X-Tenant-Id");
                String nonce = ctx.getHeaderString("X-Aster-Nonce");
                String query = ctx.getUriInfo().getRequestUri().getQuery();
                String requestHash = RequestCanonicalizer.computeRequestHash(
                    ctx.getMethod(), ctx.getUriInfo().getPath(), query, body);

                nonceService.ensureFresh(tenantId, nonce, requestHash);
                return null;
            } catch (WebApplicationException e) {
                // 透传 WebApplicationException，保留 HTTP 状态码（401/403 等）
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
          .replaceWithVoid();
    }
}
