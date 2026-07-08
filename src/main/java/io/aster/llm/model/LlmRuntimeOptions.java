package io.aster.llm.model;

/**
 * 单次 LLM 调用的运行时凭证与目标（issue: BYOK 真接入推理，Phase 2）。
 *
 * <p>把「本次请求用哪个 provider / baseUrl / apiKey」从全局 {@code LlmConfig} 解耦为 per-request，
 * 使同一执行层（{@code VertxLlmClient}）既能走平台 key，也能走用户自带 BYOK key，而无需在执行层
 * 重新长出「平台 vs BYOK」的业务分支——client 只认这个已解析好的目标。
 *
 * <ul>
 *   <li>平台调用：由 {@code LlmConfig} 填充（apiKey=平台 Vault key，provider/baseUrl=config）。</li>
 *   <li>BYOK 调用：apiKey=用户解密后的 key，provider 来自受控 allowlist，
 *       <b>baseUrl 由 aster-api 按 provider 查固定表得到，绝不接受 cloud/用户传入的 baseUrl</b>（防 SSRF）。</li>
 * </ul>
 *
 * <p>安全：本 record 携带明文 apiKey，<b>禁止</b>写入日志、审计 vault、错误体、或作为整体
 * {@code toString()} 打印。{@link #toString()} 已重写脱敏。
 */
public record LlmRuntimeOptions(
    String apiKey,
    String provider,
    String baseUrl,
    Source source
) {

    /** 凭证来源：平台 key 还是用户自带 BYOK key。 */
    public enum Source {
        PLATFORM,
        BYOK
    }

    public boolean usedByok() {
        return source == Source.BYOK;
    }

    /** 脱敏 toString：绝不泄漏 apiKey。 */
    @Override
    public String toString() {
        return "LlmRuntimeOptions{provider=" + provider
            + ", baseUrl=" + baseUrl
            + ", source=" + source
            + ", apiKey=***}";
    }
}
