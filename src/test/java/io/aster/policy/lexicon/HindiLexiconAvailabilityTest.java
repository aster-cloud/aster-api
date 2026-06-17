package io.aster.policy.lexicon;

import aster.core.lexicon.LexiconRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Hindi（hi-IN）词法表在 aster-api 运行时类路径上可用。
 *
 * <p>ADR-0017 之后，hi-IN 不再是 aster-lang-core 的内嵌 builtin —— 它由独立的
 * SPI 包 {@code aster-lang-hi} 提供，并通过 {@link LexiconRegistry} 的 SPI
 * 发现机制在运行时注册（前提是该包在 aster-api 的运行时类路径上）。
 *
 * <p>这仍是 {@code /api/v1/lexicons} 端点返回 hi-IN 的前提：{@code LexiconResource.list()}
 * 直接遍历 {@code LexiconRegistry.getInstance().availableIds()}，无论 hi-IN 来自
 * core builtin 还是 SPI 包注册，注册后即自动出现，aster-api 无需额外代码。本测试
 * 锁定这一事实，防止未来 {@code aster-lang-hi} SPI 包从运行时类路径漏掉导致
 * hi-IN 静默消失。
 */
class HindiLexiconAvailabilityTest {

    @Test
    void hiInIsProvisionedViaSpiPack() {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        assertTrue(
            registry.availableIds().contains("hi-IN"),
            "aster-lang-hi SPI 包应已把 hi-IN 注册到运行时类路径上的 LexiconRegistry；"
                + "当前 availableIds=" + registry.availableIds());
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
        // 回归：SPI 提供 hi-IN 不应影响 core 内嵌的默认 en-US baseline。
        assertTrue(LexiconRegistry.getInstance().availableIds().contains("en-US"));
    }
}
