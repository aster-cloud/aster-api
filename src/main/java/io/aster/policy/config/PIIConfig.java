package io.aster.policy.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * PII 保护配置
 *
 * 采用渐进式启用策略，默认禁用 PII 保护功能，需显式启用：
 * - aster.pii.enforce=true: 启用 PII 保护（拦截器、过滤器、日志脱敏）
 * - 其他情况: 禁用 PII 保护（默认）
 *
 * 设计理由：
 * 1. 兼容性：避免破坏现有项目，给团队时间逐步迁移
 * 2. 渐进式：允许团队按自己的节奏采纳 PII 保护
 * 3. 明确性：需要显式声明启用，避免意外启用
 * 4. 统一性：与 TypeScript 编译器的 ENFORCE_PII 环境变量保持一致
 */
@ApplicationScoped
public class PIIConfig {

    @ConfigProperty(name = "aster.pii.enforce", defaultValue = "false")
    boolean enforce;

    /**
     * 是否启用 PII 保护功能
     *
     * @return true 启用 PII 保护，false 禁用（默认）
     */
    public boolean enforce() {
        return enforce;
    }
}
