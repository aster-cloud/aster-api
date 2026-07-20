package io.aster.replay.runner;

import aster.core.lexicon.LexiconRegistry;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * runner 启动时断言 4 locale（en/zh/de/hi）SPI 全在 classpath。缺任一即 fail-closed 抛异常——
 * 防 byte-parity 头号陷阱（locale 在 :replay 是 testRuntimeOnly，若 runner 打包漏提升
 * runtimeOnly，非英文 replay 会静默分叉。此断言把「静默」变「启动即失败」）。
 */
public final class LocaleAssertion {
    private LocaleAssertion() {}

    public static final Set<String> REQUIRED_LOCALES = Set.of("en", "zh", "de", "hi");

    /**
     * 断言 4 locale 全部经 SPI 注册（生产入口）。调 core {@link LexiconRegistry} 真实 API
     * （{@link #queryRegisteredLocales()} 里 discoverPlugins() 触发 ServiceLoader 扫描 +
     * availableIds() 取已注册集合），再委托 {@link #checkAgainst(Set)}。
     */
    public static void assertAllPresent() {
        checkAgainst(queryRegisteredLocales());
    }

    /**
     * 纯校验 seam（可测，无 SPI 依赖）：present 须涵盖 REQUIRED_LOCALES，缺任一 → fail-closed 抛。
     * ★负向测试直接喂缺 locale 集合验此方法，无需 un-load SPI。
     */
    static void checkAgainst(Set<String> present) {
        Set<String> missing = new TreeSet<>(REQUIRED_LOCALES);
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("缺失 locale SPI: " + missing
                + "——检查 runner build.gradle 是否把 asterLibs.{en,zh,de,hi} 提升为 runtimeOnly");
        }
    }

    /**
     * 查询 core {@link LexiconRegistry} 已注册的 locale 短码集合。
     * <p>
     * {@code discoverPlugins()} 触发 SPI ServiceLoader 扫描（幂等 putIfAbsent，含 3 遍重试
     * 抵抗类加载竞态，见 registry 注释）；{@code availableIds()} 返回原始 BCP-47 全码
     * （en-US/zh-CN/de-DE/hi-IN）。REQUIRED_LOCALES 用短码（en/zh/de/hi），故用
     * {@link Locale#forLanguageTag(String)}#getLanguage() 归一到短码——与 registry 自身
     * {@code isValidBcp47} 的解析口径一致。
     *
     * @return 已注册 locale 短码集（如 {@code {en, zh, de, hi}}）
     */
    private static Set<String> queryRegisteredLocales() {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        registry.discoverPlugins();
        Set<String> shortCodes = new TreeSet<>();
        for (String id : registry.availableIds()) {
            String language = Locale.forLanguageTag(id).getLanguage();
            if (!language.isEmpty()) {
                shortCodes.add(language);
            }
        }
        return shortCodes;
    }
}
