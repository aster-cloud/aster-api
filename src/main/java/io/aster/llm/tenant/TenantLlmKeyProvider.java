package io.aster.llm.tenant;

/**
 * 多租户 LLM API Key 提供者
 *
 * 不同租户可使用不同的 LLM API Key。
 * 实现可从配置文件、数据库或外部密钥管理服务获取。
 */
public interface TenantLlmKeyProvider {

    /**
     * 获取指定租户的 API Key
     *
     * @param tenantId 租户 ID
     * @param provider LLM 提供商标识
     * @return API Key，如未配置返回 null
     */
    String getApiKey(String tenantId, String provider);
}
