package io.aster.replay.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ★正向：runner 的 4 locale artifact 在 testRuntime classpath（build.gradle 已加），
 *   assertAllPresent 不抛。★负向：本测试若发现缺 locale 应能定位（fail-closed 语义）。
 */
class LocaleAssertionTest {
    @Test
    void allFourLocalesPresent() {
        // 正向：4 locale SPI 都在 classpath（build.gradle runtimeOnly en/zh/de/hi + 测试同 classpath）→ 不抛。
        assertDoesNotThrow(LocaleAssertion::assertAllPresent);
    }

    @Test
    void checkPassesWhenAllPresent() {
        // 纯 seam 正向：present ⊇ REQUIRED → 不抛。
        assertDoesNotThrow(() -> LocaleAssertion.checkAgainst(
            java.util.Set.of("en", "zh", "de", "hi", "extra")));
    }

    @Test
    void checkThrowsWhenLocaleMissing() {
        // ★负向 fail-closed 证明（Codex 抓的缺失）：缺 hi → throw，异常消息含缺失 locale。
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            LocaleAssertion.checkAgainst(java.util.Set.of("en", "zh", "de")));  // 无 hi
        assertTrue(ex.getMessage().contains("hi"), "异常消息应列出缺失的 hi");
    }

    @Test
    void assertionListsRequiredLocales() {
        // 契约：REQUIRED 恰为 {en,zh,de,hi}（防未来漏 locale）。
        assertEquals(java.util.Set.of("en", "zh", "de", "hi"), LocaleAssertion.REQUIRED_LOCALES);
    }
}
