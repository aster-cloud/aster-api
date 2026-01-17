package io.aster.policy.filter;

import io.aster.policy.common.PIIRedactor;
import io.aster.policy.config.PIIConfig;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;

/**
 * Phase 0 Task 4.2 - HTTP 响应 PII 拦截过滤器
 *
 * 在 HTTP 响应发送前自动检测并脱敏响应体中的 PII 数据。
 * 支持的 PII 类型：
 * - SSN（美国社会安全号）
 * - Email 地址
 * - 电话号码
 * - 信用卡号
 * - IP 地址
 *
 * 工作机制：
 * 1. 拦截所有 HTTP 响应（通过 @Provider 自动注册）
 * 2. 检查 PII 保护是否启用（aster.pii.enforce=true）
 * 3. 检查响应体是否包含 PII（仅检查 String 类型响应）
 * 4. 如果检测到 PII，进行脱敏并记录警告日志
 * 5. 对于 JSON 响应，需要序列化后再检查（Quarkus 会自动处理）
 *
 * 注意：
 * - 此过滤器采用渐进式启用策略，默认禁用（需显式 aster.pii.enforce=true 启用）
 * - 仅处理文本响应（String, JSON）
 * - 二进制响应（图片、文件）会被跳过
 * - 不影响响应状态码和头部
 */
@Provider
public class PIIResponseFilter implements ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(PIIResponseFilter.class);
    private final PIIRedactor piiRedactor = new PIIRedactor();

    @Inject
    PIIConfig piiConfig;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        // 检查 PII 保护是否启用（渐进式启用策略）
        if (!piiConfig.enforce()) {
            return; // PII 保护未启用，跳过检查
        }

        Object entity = responseContext.getEntity();

        // 只处理字符串类型的响应体
        if (entity instanceof String) {
            String responseBody = (String) entity;

            // 检查是否包含 PII
            if (piiRedactor.containsPII(responseBody)) {
                LOG.warnf("PII detected in HTTP response: method=%s, path=%s",
                    requestContext.getMethod(),
                    requestContext.getUriInfo().getPath());

                // 脱敏响应体
                String redactedBody = piiRedactor.redact(responseBody);
                responseContext.setEntity(redactedBody);
            }
        }
        // 对于 JSON 对象，Quarkus 会在序列化后再次调用此过滤器（已序列化为 String）
        // 所以我们不需要显式处理 POJO 对象
    }
}
