package io.aster.policy.filter;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;
import java.util.Set;

/**
 * Vert.x 层 CORS 过滤器：处理 preflight（OPTIONS）和普通跨域请求。
 *
 * 通过监听 Router 事件在 Vert.x HTTP 层注册全局路由，确保 CORS 头被添加到
 * 所有响应，包括 SSE 流式响应（JAX-RS ContainerResponseFilter 无法覆盖 SSE 流）。
 */
@ApplicationScoped
public class CorsFilter {

    @ConfigProperty(name = "quarkus.http.cors.origins", defaultValue = "")
    Optional<String> allowedOrigins;

    @ConfigProperty(name = "quarkus.http.cors.methods", defaultValue = "GET,POST,PUT,DELETE,OPTIONS")
    String allowedMethods;

    @ConfigProperty(name = "quarkus.http.cors.headers", defaultValue = "accept,authorization,content-type,x-requested-with")
    String allowedHeaders;

    void onRouterReady(@Observes Router router) {
        router.route().order(-200).handler(corsHandler());
    }

    private Handler<RoutingContext> corsHandler() {
        return rc -> {
            String origin = rc.request().getHeader("Origin");
            if (origin == null || origin.isBlank() || !isOriginAllowed(origin)) {
                rc.next();
                return;
            }

            rc.response()
                .putHeader("Access-Control-Allow-Origin", origin)
                .putHeader("Access-Control-Allow-Methods", allowedMethods)
                .putHeader("Access-Control-Allow-Headers", allowedHeaders)
                .putHeader("Access-Control-Allow-Credentials", "true")
                .putHeader("Access-Control-Max-Age", "86400");

            if (rc.request().method() == HttpMethod.OPTIONS) {
                rc.response().setStatusCode(200).end();
                return;
            }

            rc.next();
        };
    }

    private boolean isOriginAllowed(String origin) {
        if (allowedOrigins.isEmpty() || allowedOrigins.get().isBlank()) {
            return false;
        }
        Set<String> origins = Set.of(allowedOrigins.get().split(","));
        return origins.contains(origin);
    }
}
