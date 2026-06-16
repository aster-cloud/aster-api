package io.aster.policy.lexicon;

import aster.core.lexicon.LexiconRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 aster-api 消费的 aster-lang-core（1.0.1）已内嵌注册 Hindi（hi-IN）词法表
 * （ADR 0017 Phase 2）。
 *
 * <p>这是 {@code /api/v1/lexicons} 端点返回 hi-IN 的前提：{@code LexiconResource.list()}
 * 直接遍历 {@code LexiconRegistry.getInstance().availableIds()}，core 把 hi-IN 作为
 * builtin 注册后即自动出现，aster-api 无需额外代码。本测试锁定这一传递性事实，
 * 防止未来 core 版本回退导致 hi-IN 静默消失。
 */
class HindiLexiconAvailabilityTest {

    @Test
    void hiInIsRegisteredAsBuiltin() {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        assertTrue(
            registry.availableIds().contains("hi-IN"),
            "core 1.0.1 应已内嵌注册 hi-IN；当前 availableIds=" + registry.availableIds());
    }

    @Test
    void hiInMetadataIsDevanagari() {
        var hi = LexiconRegistry.getInstance().getOrThrow("hi-IN");
        assertEquals("hi-IN", hi.getId());
        assertEquals("हिन्दी", hi.getName(), "Hindi 显示名应为天城文 हिन्दी");
        assertEquals("।", hi.getPunctuation().statementEnd(), "Hindi 句末符应为 danda");
    }

    @Test
    void coreStillHasEnglishBaseline() {
        // 回归：加 hi-IN 不应影响默认 en-US。
        assertTrue(LexiconRegistry.getInstance().availableIds().contains("en-US"));
    }
}
