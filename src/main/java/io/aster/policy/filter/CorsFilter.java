package io.aster.policy.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.Set;

/**
 * CORS 响应过滤器：为跨域请求添加 Access-Control-* 响应头。
 *
 * 作为 Quarkus 内置 CORS 过滤器（build-time 配置）的运行时补充，
 * 确保在任何部署环境中 CORS 头都能正确返回。
 */
@Provider
public class CorsFilter implements ContainerResponseFilter {

    @ConfigProperty(name = "quarkus.http.cors.origins", defaultValue = "")
    Optional<String> allowedOrigins;

    @ConfigProperty(name = "quarkus.http.cors.methods", defaultValue = "GET,POST,PUT,DELETE,OPTIONS")
    String allowedMethods;

    @ConfigProperty(name = "quarkus.http.cors.headers", defaultValue = "accept,authorization,content-type,x-requested-with")
    String allowedHeaders;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String origin = requestContext.getHeaderString("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }

        // 检查 origin 是否在白名单中
        if (allowedOrigins.isEmpty() || allowedOrigins.get().isBlank()) {
            return;
        }

        Set<String> origins = Set.of(allowedOrigins.get().split(","));
        if (!origins.contains(origin)) {
            return;
        }

        responseContext.getHeaders().putSingle("Access-Control-Allow-Origin", origin);
        responseContext.getHeaders().putSingle("Access-Control-Allow-Methods", allowedMethods);
        responseContext.getHeaders().putSingle("Access-Control-Allow-Headers", allowedHeaders);
        responseContext.getHeaders().putSingle("Access-Control-Allow-Credentials", "true");
        responseContext.getHeaders().putSingle("Access-Control-Max-Age", "86400");
    }
}
