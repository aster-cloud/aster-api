package io.aster.policy.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 运行时 PII 保护配置
 * <p>
 * 控制 aster-api 的**运行时** PII 保护机制：HTTP response 脱敏、日志脱敏、
 * audit redaction。这与编译时类型层 PII flow 分析（typecheck-pii.ts /
 * PiiChecker.java，见 ADR-0009 / P0-1）是**两个独立关注点**：
 * <ul>
 *   <li>类型层 PII（编译时）：永远启用，跨运行时一致——见 ADR-0009</li>
 *   <li>运行时 PII（本配置）：可配置，因为 response 脱敏/日志脱敏有性能开销，
 *       某些环境（如纯内部 API、调试环境）可能合法选择禁用</li>
 * </ul>
 * <p>
 * 配置：
 * <ul>
 *   <li>aster.pii.enforce=true（默认）: 启用所有运行时 PII 保护（拦截器、
 *       响应过滤器、日志脱敏）</li>
 *   <li>aster.pii.enforce=false: 禁用运行时保护（仅限开发/调试环境）</li>
 * </ul>
 * <p>
 * 注意：默认值与 src/main/resources/application.properties 一致（生产默认
 * 启用）。之前 default="false" 是历史包袱，已修正为 default="true" 与生产
 * 配置对齐——避免在 properties 未设时回退到不安全的默认值。
 */
@ApplicationScoped
public class PIIConfig {

    @ConfigProperty(name = "aster.pii.enforce", defaultValue = "true")
    boolean enforce;

    /**
     * 是否启用运行时 PII 保护功能
     *
     * @return true 启用（默认），false 禁用（仅开发环境）
     */
    public boolean enforce() {
        return enforce;
    }
}
