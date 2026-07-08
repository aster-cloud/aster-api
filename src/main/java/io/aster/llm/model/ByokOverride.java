package io.aster.llm.model;

/**
 * 一次请求携带的 BYOK 覆盖凭证（issue: BYOK 真接入推理，Phase 2）。
 *
 * <p>由 aster-cloud 在【已 HMAC 验签】的内部请求 body 顶层以 {@code _byok} envelope 传入，
 * 经 {@code AiAssistantResource} 从原始 body 解析、剥离后构造，<b>不进业务 DTO、不进 PromptComposer、
 * 不进审计 vault、不打日志</b>。
 *
 * <p>安全：只携带 provider + 用户解密后的 apiKey。<b>不含 baseUrl</b>——baseUrl 由 aster-api 按
 * provider 查固定 allowlist 得到，绝不接受 cloud/用户传入的 URL（防 SSRF）。{@link #toString()} 脱敏。
 */
public record ByokOverride(String provider, String apiKey) {

    public boolean isValid() {
        return provider != null && !provider.isBlank()
            && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "ByokOverride{provider=" + provider + ", apiKey=***}";
    }
}
