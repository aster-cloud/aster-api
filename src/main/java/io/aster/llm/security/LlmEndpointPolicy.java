package io.aster.llm.security;

import io.aster.security.net.SsrfGuard;
import io.aster.security.net.SsrfViolation;
import io.aster.security.net.ValidatedEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * BYOK 自定义 LLM endpoint 管理员 allowlist。
 *
 * <p>GA 策略是纯 allowlist：用户传入 baseUrl 只有命中官方内置端点或管理员配置项
 * {@code aster.llm.byok.endpoint-allowlist} 才能用于推理。任意用户 URL 不开放。
 *
 * <p>配置格式（逗号分隔 list 均可）：
 * <ul>
 *   <li>{@code llm-gateway.example.com}</li>
 *   <li>{@code llm-gateway.example.com:8443}</li>
 *   <li>{@code https://llm-gateway.example.com/v1}</li>
 * </ul>
 */
@ApplicationScoped
public class LlmEndpointPolicy {

    private static final List<AllowedEndpoint> BUILTIN = List.of(
        new AllowedEndpoint("api.openai.com", 443, ""),
        new AllowedEndpoint("api.anthropic.com", 443, "")
    );

    @Inject
    SsrfGuard ssrfGuard;

    @ConfigProperty(name = "aster.llm.byok.endpoint-allowlist")
    Optional<List<String>> endpointAllowlist = Optional.empty();

    /**
     * 校验 BYOK 自定义 baseUrl。SSRF guard 先规范化和拒绝私网 IP，再做管理员 allowlist 匹配。
     */
    public ValidatedEndpoint validateByokEndpoint(String provider, String baseUrl) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!"openai".equals(normalizedProvider) && !"anthropic".equals(normalizedProvider)) {
            throw new IllegalArgumentException("不支持的 BYOK provider: " + provider);
        }
        ValidatedEndpoint endpoint = ssrfGuard.validate(baseUrl);
        if (!isAllowed(endpoint)) {
            throw new IllegalArgumentException("BYOK baseUrl 未命中管理员 allowlist: " + endpoint.canonicalHost());
        }
        return endpoint;
    }

    public boolean isAllowed(ValidatedEndpoint endpoint) {
        for (AllowedEndpoint allowed : allowedEndpoints()) {
            if (allowed.matches(endpoint)) {
                return true;
            }
        }
        return false;
    }

    List<AllowedEndpoint> allowedEndpoints() {
        List<AllowedEndpoint> allowed = new ArrayList<>(BUILTIN);
        for (String entry : endpointAllowlist.orElse(List.of())) {
            if (entry != null && !entry.isBlank()) {
                allowed.add(parseAllowedEndpoint(entry));
            }
        }
        return allowed;
    }

    private AllowedEndpoint parseAllowedEndpoint(String entry) {
        String raw = entry.trim();
        try {
            URI uri = raw.contains("://") ? URI.create(raw) : URI.create("https://" + raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new SsrfViolation("allowlist 只允许 https endpoint");
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new SsrfViolation("allowlist endpoint 不允许 userinfo/query/fragment");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new SsrfViolation("allowlist endpoint 缺少 host");
            }
            int port = uri.getPort() == -1 ? 443 : uri.getPort();
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                path = "";
            }
            ValidatedEndpoint normalized = ssrfGuard.validate("https://" + host + (port == 443 ? "" : ":" + port) + path);
            return new AllowedEndpoint(normalized.canonicalHost(), normalized.port(), normalized.pathPrefix());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 BYOK endpoint allowlist entry: " + entry, e);
        }
    }

    record AllowedEndpoint(String host, int port, String pathPrefix) {
        boolean matches(ValidatedEndpoint endpoint) {
            if (!host.equals(endpoint.canonicalHost()) || port != endpoint.port()) {
                return false;
            }
            return pathPrefix == null || pathPrefix.isBlank()
                || endpoint.pathPrefix().equals(pathPrefix)
                || endpoint.pathPrefix().startsWith(pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + "/");
        }
    }
}
