package io.aster.security.apikey;

import org.junit.jupiter.api.Test;

import static io.aster.security.apikey.InternalCallerFilter.Classification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * R27-Minor-3：InternalCallerFilter 单元测试。
 *
 * <p>测试 path 分类 + 旁路决策的纯函数 {@link InternalCallerFilter#classify}，
 * 以及 HMAC canonical 形式（method + path + ts）。不依赖 Quarkus 上下文。
 *
 * <p>覆盖的关键回归点：
 * <ul>
 *   <li>R23-Critical-2: AI 端点未签名时必须 REQUIRE_HMAC</li>
 *   <li>R25-Critical-1: matrix-param 不应绕过分类</li>
 *   <li>R25-Major-3: aster.security.ai.sse.public 只放过 SSE 三段，/complete 仍要 HMAC</li>
 *   <li>aster.security.ai.public 全量旁路（粗粒度）</li>
 * </ul>
 */
class InternalCallerFilterTest {

    // ============================================================
    // Classification: 路径不在管辖范围 → NOT_PROTECTED
    // ============================================================

    @Test
    void unrelatedPathsAreNotProtected() {
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/lexicons", false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/policies/evaluate", false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/q/health", false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/", false, false, false));
    }

    @Test
    void aiPathWithoutSubResourceNotProtected() {
        // /api/v1/ai 本身（没有 sub-resource）不属于 LLM 端点，不在管辖
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/ai", false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/ai/", false, false, false));
    }

    // ============================================================
    // Classification: evaluate-source 默认需要 HMAC
    // ============================================================

    @Test
    void evaluateSourceRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                false, false, false));
    }

    @Test
    void evaluateSourcePublicFlagBypasses() {
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ true, false, false));
    }

    // ============================================================
    // Classification: AI 端点 — 默认 / ai.public / ai.sse.public 三个层级
    // ============================================================

    @Test
    void aiCompleteRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete", false, false, false));
    }

    @Test
    void aiGenerateRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/generate", false, false, false));
    }

    @Test
    void aiPublicBypassesEverything() {
        // 粗粒度全量旁路 —— /complete 也放过
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, /*aiPublic*/ true, false));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, true, false));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/translate", // 未来端点
                false, true, false));
    }

    @Test
    void aiSsePublicBypassesOnlySseEndpoints() {
        // R25-Major-3: ai.sse.public 只放 generate/explain/suggest
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, false, /*aiSsePublic*/ true));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/explain",
                false, false, true));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/suggest",
                false, false, true));
    }

    @Test
    void aiSsePublicDoesNotAffectComplete() {
        // R25-Major-3 关键不变式：/complete 永远不被 sse.public 影响
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, false, /*aiSsePublic*/ true));
    }

    @Test
    void aiSsePublicDoesNotAffectUnknownFutureAiEndpoint() {
        // 未来加一个 /api/v1/ai/translate —— 不在 SSE 白名单 set 里，
        // 设了 ai.sse.public 也不放过，必须显式扩展 AI_SSE_PATHS
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/translate",
                false, false, true));
    }

    @Test
    void aiPublicTakesPrecedenceOverSsePublic() {
        // ai.public 是更粗粒度的旁路；同时设 sse.public 时它优先（reviewer 确认这是
        // 当前实现的优先级顺序，operator 应当显式选择）
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, /*aiPublic*/ true, /*aiSsePublic*/ true));
    }

    // ============================================================
    // Classification: matrix-param 不应改变分类
    // 这里 classify 收到的是 normalized path（PathNormalizer.normalize 已剥离 ;...）
    // 但保险起见也防御 NOT_PROTECTED 的 matrix-param case
    // ============================================================

    @Test
    void classifyAssumesAlreadyNormalizedPath() {
        // classify 不做 normalization —— 调用方（filter()）必须先用 PathNormalizer。
        // 如果有人把 raw path 传进来，matrix params 会让分类变化。
        // 这个测试记录"约定 = 必须先 normalize"。
        Classification rawPath = InternalCallerFilter.classify(
            "/api/v1/ai/complete;jsessionid=abc", false, false, false);
        // 因为 isAi 用 startsWith，所以 /api/v1/ai/complete;x 仍被识别 isAi=true。
        // 但更重要的：filter() 入口已 normalize → 实际调用 classify 时不会有 ;。
        // 此处仅断言"原始 raw 路径形态下行为是 REQUIRE_HMAC"，即未 normalize 不会
        // 引入 BYPASS_OK 这样的危险错分类。
        assertNotEquals(Classification.BYPASS_OK, rawPath);
    }

    // ============================================================
    // HMAC sign(): canonical 形式 method + "\n" + path + "\n" + ts
    // ============================================================

    @Test
    void signProducesDeterministicHex() {
        // 同样输入产生同样输出
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        assertEquals(a, b);
        // 64 hex chars (HmacSHA256 → 32 bytes)
        assertEquals(64, a.length());
        // 仅 hex 字符
        org.junit.jupiter.api.Assertions.assertTrue(a.matches("[0-9a-f]+"));
    }

    @Test
    void signWithDifferentPathProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/generate\n1700000000");
        assertNotEquals(a, b, "path 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentTimestampProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000001");
        assertNotEquals(a, b, "ts 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentKeyProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k1", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k2", "POST\n/api/v1/ai/complete\n1700000000");
        assertNotEquals(a, b, "key 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentMethodProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "GET\n/api/v1/ai/complete\n1700000000");
        assertNotEquals(a, b, "method 不同必须产生不同签名");
    }
}
