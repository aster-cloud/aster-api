package io.aster.policy.security;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 请求签名验证过滤器
 *
 * 拦截所有请求，验证 HMAC 签名和 Nonce，防止请求篡改和重放攻击
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class RequestSignatureFilter implements ContainerRequestFilter {

    @Inject
    HmacSignatureService hmacSignatureService;

    @Inject
    NonceService nonceService;

    @ConfigProperty(name = "aster.security.signature.enabled", defaultValue = "false")
    boolean signatureEnabled;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        // 跳过健康检查和内部端点（支持带/不带前导斜杠的路径）
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/q/") || path.startsWith("q/")
                || path.startsWith("/internal/") || path.startsWith("internal/")) {
            return;
        }

        // 如果签名验证未启用，跳过
        if (!signatureEnabled) {
            return;
        }

        // 读取请求体（只能读取一次，需要缓存）
        byte[] body = ctx.getEntityStream().readAllBytes();
        ctx.setEntityStream(new ByteArrayInputStream(body));

        // 验证签名
        hmacSignatureService.verify(ctx, body);

        // 验证 Nonce（防重放）
        String tenantId = ctx.getHeaderString("X-Tenant-Id");
        String nonce = ctx.getHeaderString("X-Aster-Nonce");
        String query = ctx.getUriInfo().getRequestUri().getQuery();
        String requestHash = RequestCanonicalizer.computeRequestHash(ctx.getMethod(), ctx.getUriInfo().getPath(), query, body);

        nonceService.ensureFresh(tenantId, nonce, requestHash);
    }
}
