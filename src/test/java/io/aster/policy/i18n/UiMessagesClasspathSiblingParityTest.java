package io.aster.policy.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * aster-api classpath ui-messages 副本与语言包真相源的 byte-identical 守门。
 *
 * <p>背景（单源漂移审计）：aster-api 的 {@code src/main/resources/ui-messages/*.json}
 * 是各语言包 SPI 源的 classpath 同步副本（后端 {@link UiMessagesService} 从 classpath
 * 加载）。这两处是**两个独立入库副本**，历史上靠手工 cp 同步——后端改 classpath 忘同步
 * 源（或反之）会静默漂移。本测试断言四语 classpath 副本与 SPI 源逐字节一致。
 *
 * <p>真相源位置（与 CI 兄弟仓 checkout 布局一致，均为 aster-api 的并列目录）：
 * <ul>
 *   <li>en/zh/de: {@code ../aster-lang-locales/locales/<lang>/src/main/resources/ui-messages/<id>.json}</li>
 *   <li>hi: {@code ../aster-lang-hi/src/main/resources/ui-messages/hi-IN.json}</li>
 * </ul>
 *
 * <p>本地缺兄弟仓时 {@link Assumptions#assumeTrue} 跳过（开发者未必并列 checkout 全生态）；
 * CI 的 setup-aster-build 会 checkout locales + hi，故 CI 强制执行。
 */
@DisplayName("ui-messages classpath 副本 ↔ 语言包源 sibling parity")
class UiMessagesClasspathSiblingParityTest {

    /** classpath 副本相对路径 → SPI 源相对路径（相对 aster-api 项目根）。 */
    private static final Map<String, String> COPY_TO_SOURCE = Map.of(
        "src/main/resources/ui-messages/en-US.json",
        "../aster-lang-locales/locales/en/src/main/resources/ui-messages/en-US.json",
        "src/main/resources/ui-messages/zh-CN.json",
        "../aster-lang-locales/locales/zh/src/main/resources/ui-messages/zh-CN.json",
        "src/main/resources/ui-messages/de-DE.json",
        "../aster-lang-locales/locales/de/src/main/resources/ui-messages/de-DE.json",
        "src/main/resources/ui-messages/hi-IN.json",
        "../aster-lang-hi/src/main/resources/ui-messages/hi-IN.json"
    );

    @Test
    @DisplayName("四语 classpath 副本与 SPI 源逐字节一致（兄弟仓存在时）")
    void classpathCopiesMatchSpiSources() throws Exception {
        for (var entry : COPY_TO_SOURCE.entrySet()) {
            Path copy = Path.of(entry.getKey());
            Path source = Path.of(entry.getValue());

            // classpath 副本是入库产物，**必须**存在——无条件断言（缺失=真 bug，不能 skip 假绿）。
            assertTrue(Files.isRegularFile(copy), "classpath 副本缺失（入库产物必须存在）: " + copy);

            // 源缺失=兄弟仓未并列 checkout。fail-closed：CI 或显式要求时强制存在（防未来 job
            // 绕过 setup 而静默 skip 假绿）；本地未 checkout 全生态时才 skip。
            if (!Files.isRegularFile(source)) {
                if (requireParity()) {
                    fail("CI/强制模式下语言包源必须存在（setup 应 checkout locales/hi）: " + source);
                }
                assumeTrue(false, "本地未并列 checkout 兄弟仓，跳过跨仓 parity: " + source);
            }

            assertArrayEquals(
                Files.readAllBytes(source),
                Files.readAllBytes(copy),
                copy + " 与真相源 " + source + " 不一致——请从语言包源同步 classpath 副本"
            );
        }
    }

    /**
     * CI 时要求跨仓 parity 必须真跑（fail-closed），本地缺兄弟仓允许 skip。
     *
     * <p>用 env var（GitHub Actions 自动设 CI/GITHUB_ACTIONS，gradle 转发父进程 env 给
     * test fork）而非 -D 系统属性——本仓 test 任务未转发 -D 到 fork JVM，系统属性读不到。
     * 需在非 GitHub CI 强制时，设 env {@code ASTER_UI_MESSAGES_PARITY_REQUIRED=true}。
     */
    private static boolean requireParity() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"))
            || Boolean.parseBoolean(System.getenv().getOrDefault("GITHUB_ACTIONS", "false"))
            || Boolean.parseBoolean(System.getenv().getOrDefault("ASTER_UI_MESSAGES_PARITY_REQUIRED", "false"));
    }
}
