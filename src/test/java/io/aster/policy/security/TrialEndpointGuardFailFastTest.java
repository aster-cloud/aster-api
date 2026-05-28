package io.aster.policy.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R29+ Codex 复审动作 #1：危险配置在生产 profile 下必须 fail-fast。
 *
 * <p>历史：原实现只 WARN，operator 容易把 marketing trial 端点变成裸奔
 * 匿名执行入口。本测试验证：
 *
 * <ul>
 *   <li>{@code .public + .trial} 同开 → prod fail-fast，dev 仅 warn。</li>
 *   <li>{@code .trial} 开启但 allowed-origins 为空 → prod fail-fast，dev 仅 warn。</li>
 *   <li>正常配置 → 任何 profile 都不抛。</li>
 * </ul>
 *
 * <p>LaunchMode 通过设置 system property {@code quarkus.test.profile} 切换；
 * 我们在测试里直接用 {@code LaunchMode.set(...)} 不可用，因此通过启动期切换
 * mock 的方式。这里采用最直接路径：测试运行在 TEST mode，硬编码确认 dev/test
 * 不抛；prod fail-fast 通过临时反射 LaunchMode#current 验证。
 */
class TrialEndpointGuardFailFastTest {

    private LaunchMode originalMode;

    @BeforeEach
    void capture() throws Exception {
        // 抓快照便于 restore
        originalMode = LaunchMode.current();
    }

    @AfterEach
    void restore() {
        // 测试运行在 LaunchMode.TEST；切换 prod 后必须复位
        LaunchMode.set(originalMode);
    }

    private TrialEndpointGuard newGuard(boolean enabled,
                                         boolean evaluateSourcePublic,
                                         String allowedOriginsCsv,
                                         boolean trustForwardedFor) throws Exception {
        TrialEndpointGuard g = new TrialEndpointGuard();
        set(g, "enabled", enabled);
        set(g, "evaluateSourcePublic", evaluateSourcePublic);
        set(g, "allowedOriginsCsv", allowedOriginsCsv);
        set(g, "trustForwardedFor", trustForwardedFor);
        set(g, "maxBodyBytes", 32768);
        set(g, "perIpMinuteMax", 10);
        set(g, "perIpHourMax", 60);
        set(g, "perIpConcurrentMax", 2);
        return g;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = TrialEndpointGuard.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("R29+: prod profile + .public+.trial 同开 → IllegalStateException")
    void prodFailsFastOnPublicAndTrialBothEnabled() throws Exception {
        LaunchMode.set(LaunchMode.NORMAL);

        TrialEndpointGuard g = newGuard(
            /*enabled*/ true,
            /*evaluateSourcePublic*/ true,
            /*allowedOriginsCsv*/ "https://aster-lang.dev",
            /*trustForwardedFor*/ false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> g.auditStartupConfig(new StartupEvent()));
        assertTrue(ex.getMessage().contains(".public") || ex.getMessage().contains(".trial"),
            "fail-fast 消息必须提到冲突的两个 flag");
    }

    @Test
    @DisplayName("R29+: prod profile + trial 开但 allowed-origins 为空 → IllegalStateException")
    void prodFailsFastOnTrialWithEmptyOrigins() throws Exception {
        LaunchMode.set(LaunchMode.NORMAL);

        TrialEndpointGuard g = newGuard(
            /*enabled*/ true,
            /*evaluateSourcePublic*/ false,
            /*allowedOriginsCsv*/ "",
            /*trustForwardedFor*/ false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> g.auditStartupConfig(new StartupEvent()));
        assertTrue(ex.getMessage().contains("allowed-origins"),
            "fail-fast 消息必须提到 allowed-origins 为空");
    }

    @Test
    @DisplayName("R29+: dev/test profile 同样配置只 warn，不抛")
    void devTestProfileOnlyWarns() throws Exception {
        LaunchMode.set(LaunchMode.TEST);

        TrialEndpointGuard g = newGuard(
            /*enabled*/ true,
            /*evaluateSourcePublic*/ true,
            /*allowedOriginsCsv*/ "",
            /*trustForwardedFor*/ false);

        assertDoesNotThrow(() -> g.auditStartupConfig(new StartupEvent()),
            "dev/test profile 必须只 warn，不能 fail-fast —— 否则开发体验断裂");
    }

    @Test
    @DisplayName("R29+: 正常配置 → prod 也通过，不误伤")
    void normalConfigPassesInProd() throws Exception {
        LaunchMode.set(LaunchMode.NORMAL);

        TrialEndpointGuard g = newGuard(
            /*enabled*/ true,
            /*evaluateSourcePublic*/ false,
            /*allowedOriginsCsv*/ "https://aster-lang.dev,https://aster-lang.cloud",
            /*trustForwardedFor*/ false);

        assertDoesNotThrow(() -> g.auditStartupConfig(new StartupEvent()));
    }

    @Test
    @DisplayName("R29+: trial 关闭时 prod 不做任何 fail-fast 检查（disabled 模式）")
    void disabledTrialNeverFailsFast() throws Exception {
        LaunchMode.set(LaunchMode.NORMAL);

        TrialEndpointGuard g = newGuard(
            /*enabled*/ false,
            /*evaluateSourcePublic*/ true,  // 即便同时开 .public，trial 自身关闭就不审计
            /*allowedOriginsCsv*/ "",
            /*trustForwardedFor*/ false);

        assertDoesNotThrow(() -> g.auditStartupConfig(new StartupEvent()));
    }
}
