package io.aster.llm.tenant;

import io.aster.llm.config.LlmConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * 基于配置文件的 API Key 提供者
 *
 * 当前实现使用全局 API Key，后续可扩展为：
 * - 环境变量 ASTER_LLM_API_KEY_{TENANT_ID} 租户级覆盖
 * - 数据库存储的加密 Key
 */
@ApplicationScoped
public class ConfigTenantLlmKeyProvider implements TenantLlmKeyProvider {

    private static final Logger LOG = Logger.getLogger(ConfigTenantLlmKeyProvider.class);

    @Inject
    LlmConfig config;

    @Override
    public String getApiKey(String tenantId, String provider) {
        // 先尝试租户专属 Key（通过环境变量）
        String tenantEnvKey = "ASTER_LLM_API_KEY_" + tenantId.toUpperCase().replace("-", "_");
        String tenantApiKey = System.getenv(tenantEnvKey);
        if (tenantApiKey != null && !tenantApiKey.isBlank()) {
            LOG.debugf("使用租户专属 API Key: tenant=%s", tenantId);
            return tenantApiKey;
        }

        // 回退到全局 Key
        String globalKey = config.apiKey().orElse(null);
        if (globalKey != null && !globalKey.isBlank()) {
            return globalKey;
        }

        LOG.warnf("未找到 API Key: tenant=%s, provider=%s", tenantId, provider);
        return null;
    }
}
