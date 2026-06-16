package io.aster.policy.graphql;

import io.aster.security.apikey.ApiKeyPerimeter;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * #55 (CRITICAL): 在 Vert.x HTTP 层为 {@code /graphql} 路由注册 API-key 认证 handler。
 *
 * <p>背景：{@code /graphql} 由 SmallRye/Vert.x 直接处理，**不经过** RESTEasy Reactive
 * 的 {@code @ServerRequestFilter}（ApiKeyAuthFilter）或 JAX-RS {@code ContainerRequestFilter}
 * （TenantFilter / RoleEnforcementFilter）。因此此前 {@code PolicyGraphQLResource} 完全无鉴权、
 * 且从未验证的 {@code X-Tenant-Id} 头推导租户 —— 任何人都能对任意租户 CRUD 策略。
 *
 * <p>本 handler 复刻 {@link io.aster.policy.filter.CorsFilter} 的机制（监听 {@code Router}
 * 就绪事件、注册一个 {@code order(...)} 路由），在请求进入 GraphQL 引擎前运行与
 * {@code ApiKeyAuthFilter} **同一套**验证逻辑（{@link ApiKeyPerimeter}），未认证请求直接 401。
 * 验证通过后把权威身份（tenant / role / userId / apiKeyId）写入 {@link RoutingContext}，
 * 供 {@code PolicyGraphQLResource} 读取（租户与角色都来自已验证身份，而非客户端头）。
 *
 * <p>order 选择：CORS handler 在 {@code order(-200)}（且会对 OPTIONS preflight 提前 end，
 * 故 preflight 不会到这里）。本 handler 放在 {@code order(-150)}，即 CORS 之后、GraphQL
 * 引擎默认路由之前。
 */
@ApplicationScoped
public class GraphQLApiKeyAuthHandler {

    private static final Logger LOG = Logger.getLogger(GraphQLApiKeyAuthHandler.class);

    /** 与 REST 侧 ApiKeyAuthFilter 共用同一开关，保持行为一致。 */
    @ConfigProperty(name = "aster.security.apikey.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "quarkus.smallrye-graphql.root-path", defaultValue = "/graphql")
    String graphqlRootPath;

    @Inject
    ApiKeyPerimeter perimeter;

    void onRouterReady(@Observes Router router) {
        // pathRegex 覆盖 /graphql 及其子路径（/graphql/schema.graphql 等），但 schema
        // 端点本身只读且 TenantFilter 已豁免；这里仍要求认证以彻底关闭未鉴权面。
        router.routeWithRegex("^" + java.util.regex.Pattern.quote(graphqlRootPath) + "(/.*)?$")
            .order(-150)
            .handler(authHandler());
    }

    private Handler<RoutingContext> authHandler() {
        return rc -> {
            if (!enabled) {
                rc.next();
                return;
            }
            // CORS preflight 已由 CorsFilter 提前 end；防御性放行任何漏到这里的 OPTIONS。
            if (rc.request().method() == io.vertx.core.http.HttpMethod.OPTIONS) {
                rc.next();
                return;
            }

            String auth = rc.request().getHeader("Authorization");
            String tenantHeader = rc.request().getHeader("X-Tenant-Id");

            // verify 在 cache-miss 时会做阻塞 IO（cloud verify / redis）—— 不能在
            // event-loop 上跑，放到 worker 线程。cache-hit 路径也走 worker，开销可忽略。
            rc.vertx().executeBlocking(() -> perimeter.authenticate(auth, tenantHeader), false)
                .onSuccess(decision -> {
                    if (!decision.allowed()) {
                        reject(rc, decision.statusCode(), decision.reason());
                        return;
                    }
                    // 把权威身份写入 RoutingContext，供 PolicyGraphQLResource 读取。
                    rc.put(ApiKeyPerimeter.PROP_TENANT_ID, decision.tenantId());
                    rc.put(ApiKeyPerimeter.PROP_ROLE, decision.role());
                    if (decision.userId() != null) rc.put(ApiKeyPerimeter.PROP_USER_ID, decision.userId());
                    if (decision.apiKeyId() != null) rc.put(ApiKeyPerimeter.PROP_API_KEY_ID, decision.apiKeyId());
                    rc.next();
                })
                .onFailure(err -> {
                    LOG.errorf(err, "graphql apikey auth handler error");
                    reject(rc, 401, "verify_error");
                });
        };
    }

    private static void reject(RoutingContext rc, int status, String reason) {
        if (rc.response().ended()) {
            return;
        }
        String body = new JsonObject()
            .put("error", status == 403 ? "forbidden" : "unauthorized")
            .put("reason", reason == null ? "unauthorized" : reason)
            .put("message", "GraphQL access requires a valid API key bound to the target tenant.")
            .encode();
        rc.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(body);
    }
}
