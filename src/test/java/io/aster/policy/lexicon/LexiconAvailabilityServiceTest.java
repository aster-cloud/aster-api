package io.aster.policy.lexicon;

import aster.core.lexicon.LexiconRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LexiconAvailabilityService 单元测试（轻量，不启动 Quarkus / 不接 Redis）。
 *
 * <p>Redis 持久 + 广播由 {@code Instance<RedisDataSource>.isUnsatisfied()} 守卫——本测试
 * 不注入 Redis，故走 fail-open 分支（纯本副本内存行为）。验证：
 * <ul>
 *   <li>applyRemoteEvent 解析 "disable:&lt;id&gt;" / "enable:&lt;id&gt;" 并应用到 registry（幂等）。</li>
 *   <li>畸形 / 未知事件被安全忽略（不抛）。</li>
 *   <li>disable/enable 在 Redis 缺失时不抛（fail-open）。</li>
 * </ul>
 * 真正的跨副本 Redis pub/sub + SET 往返由 {@code LexiconAvailabilityServiceRedisTest}（@QuarkusTest）覆盖。
 */
@DisplayName("LexiconAvailabilityService 跨副本可用性（单元）")
class LexiconAvailabilityServiceTest {

    private LexiconAvailabilityService svc;

    @BeforeEach
    void setUp() {
        svc = new LexiconAvailabilityService();
        // 不注入 redisDataSource → redisUnavailable()=true（Instance 为 null）→ fail-open。
        // 恢复 baseline：zh-CN/de-DE 可用。
        LexiconRegistry.getInstance().markAvailable("zh-CN");
        LexiconRegistry.getInstance().markAvailable("de-DE");
        if (!LexiconRegistry.getInstance().has("zh-CN")
            || !LexiconRegistry.getInstance().has("de-DE")) {
            LexiconRegistry.getInstance().discoverPlugins(LexiconPlugin().getClassLoader());
        }
    }

    @AfterEach
    void tearDown() {
        LexiconRegistry.getInstance().markAvailable("zh-CN");
        LexiconRegistry.getInstance().markAvailable("de-DE");
    }

    private static Class<?> LexiconPlugin() {
        return aster.core.lexicon.LexiconPlugin.class;
    }

    private void applyRemote(String payload) throws Exception {
        Method m = LexiconAvailabilityService.class.getDeclaredMethod("applyRemoteEvent", String.class);
        m.setAccessible(true);
        m.invoke(svc, payload);
    }

    @Test
    @DisplayName("收到 disable 广播 → 本地 markUnavailable")
    void applyRemoteDisable() throws Exception {
        assertThat(LexiconRegistry.getInstance().has("zh-CN")).isTrue();
        applyRemote("disable:zh-CN");
        assertThat(LexiconRegistry.getInstance().has("zh-CN"))
            .as("收到 disable 广播后 zh-CN 应在本副本软下线")
            .isFalse();
    }

    @Test
    @DisplayName("收到 enable 广播 → 本地 markAvailable（恢复）")
    void applyRemoteEnable() throws Exception {
        applyRemote("disable:de-DE");
        assertThat(LexiconRegistry.getInstance().has("de-DE")).isFalse();
        applyRemote("enable:de-DE");
        assertThat(LexiconRegistry.getInstance().has("de-DE"))
            .as("收到 enable 广播后 de-DE 应恢复可用")
            .isTrue();
    }

    @Test
    @DisplayName("畸形 / 未知广播事件被安全忽略，不抛")
    void malformedEventsIgnored() throws Exception {
        applyRemote(null);
        applyRemote("");
        applyRemote("nocolon");
        applyRemote(":zh-CN");          // 空 op
        applyRemote("disable:");         // 空 id
        applyRemote("frobnicate:zh-CN"); // 未知 op
        // 上述都不应改变 zh-CN 可用性（baseline 可用）。
        assertThat(LexiconRegistry.getInstance().has("zh-CN"))
            .as("畸形/未知事件不应误改可用性")
            .isTrue();
    }

    @Test
    @DisplayName("Redis 缺失时 disable/enable fail-open：本地生效，不抛")
    void disableEnableFailOpenWithoutRedis() {
        // redisDataSource 未注入 → persistAndBroadcast 走 redisUnavailable() 早返回，不抛。
        boolean changed = svc.disable("zh-CN");
        assertThat(changed).isTrue();
        assertThat(LexiconRegistry.getInstance().has("zh-CN")).isFalse();

        boolean changedBack = svc.enable("zh-CN");
        assertThat(changedBack).isTrue();
        assertThat(LexiconRegistry.getInstance().has("zh-CN")).isTrue();
    }
}
