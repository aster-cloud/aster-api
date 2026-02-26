package io.aster.policy.filter;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.Set;

/**
 * 运行时 CORS 过滤器：处理 preflight（OPTIONS）和普通跨域请求。
 *
 * 同时实现 ContainerRequestFilter（拦截 OPTIONS 并返回 CORS 头）
 * 和 ContainerResponseFilter（为普通请求添加 CORS 头）。
 *
 * 优先级设为 AUTHENTICATION - 1，确保在所有业务过滤器之前执行。
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 1)
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @ConfigProperty(name = "quarkus.http.cors.origins", defaultValue = "")
    Optional<String> allowedOrigins;

    @ConfigProperty(name = "quarkus.http.cors.methods", defaultValue = "GET,POST,PUT,DELETE,OPTIONS")
    String allowedMethods;

    @ConfigProperty(name = "quarkus.http.cors.headers", defaultValue = "accept,authorization,content-type,x-requested-with")
    String allowedHeaders;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String origin = requestContext.getHeaderString("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }

        if (!isOriginAllowed(origin)) {
            return;
        }

        // OPTIONS preflight：直接返回 200 + CORS 头
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            requestContext.abortWith(
                Response.ok()
                    .header("Access-Control-Allow-Origin", origin)
                    .header("Access-Control-Allow-Methods", allowedMethods)
                    .header("Access-Control-Allow-Headers", allowedHeaders)
                    .header("Access-Control-Allow-Credentials", "true")
                    .header("Access-Control-Max-Age", "86400")
                    .build()
            );
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String origin = requestContext.getHeaderString("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }

        if (!isOriginAllowed(origin)) {
            return;
        }

        responseContext.getHeaders().putSingle("Access-Control-Allow-Origin", origin);
        responseContext.getHeaders().putSingle("Access-Control-Allow-Methods", allowedMethods);
        responseContext.getHeaders().putSingle("Access-Control-Allow-Headers", allowedHeaders);
        responseContext.getHeaders().putSingle("Access-Control-Allow-Credentials", "true");
        responseContext.getHeaders().putSingle("Access-Control-Max-Age", "86400");
    }

    private boolean isOriginAllowed(String origin) {
        if (allowedOrigins.isEmpty() || allowedOrigins.get().isBlank()) {
            return false;
        }
        Set<String> origins = Set.of(allowedOrigins.get().split(","));
        return origins.contains(origin);
    }
}
