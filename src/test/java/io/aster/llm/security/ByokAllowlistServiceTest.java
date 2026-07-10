package io.aster.llm.security;

import io.aster.security.net.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ByokAllowlistService 动态 allowlist")
class ByokAllowlistServiceTest {

    private ByokAllowlistService svc;

    @BeforeEach
    void setUp() {
        svc = new ByokAllowlistService();
        svc.ssrfGuard = new SsrfGuard(host -> List.of(ip(switch (host) {
            case "metadata.google.internal" -> "169.254.169.254";
            case "internal.example.com" -> "10.0.0.8";
            default -> "93.184.216.34";
        })));
        // redisDataSource 未注入：add/remove 走 Redis fail-open，本副本内存仍生效。
    }

    @Test
    @DisplayName("add 先过 SsrfGuard 规范化，Redis 缺失时本地生效")
    void addNormalizesAndWorksWithoutRedis() {
        ByokAllowlistService.MutationResult result = svc.add("Right.Codes");

        assertThat(result.host()).isEqualTo("right.codes");
        assertThat(result.tenantScope()).isNull();
        assertThat(result.changed()).isTrue();
        assertThat(svc.allowedHosts()).containsExactly("right.codes");
    }

    @Test
    @DisplayName("remove 移除规范化 host，Redis 缺失时不抛")
    void removeWorksWithoutRedis() {
        svc.add("right.codes");

        ByokAllowlistService.MutationResult result = svc.remove("https://right.codes");

        assertThat(result.host()).isEqualTo("right.codes");
        assertThat(result.changed()).isTrue();
        assertThat(svc.allowedHosts()).isEmpty();
    }

    @Test
    @DisplayName("add 拒绝私网/元数据解析结果，Redis fail-open 不影响 SSRF fail-closed")
    void addRejectsForbiddenIp() {
        assertThatThrownBy(() -> svc.add("metadata.google.internal"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("禁止");
        assertThatThrownBy(() -> svc.add("internal.example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("禁止");
        assertThat(svc.allowedHosts()).isEmpty();
    }

    @Test
    @DisplayName("动态 GA 只接受 host，不接受 path 或非 443 port")
    void dynamicEntriesAreHostOnly() {
        assertThatThrownBy(() -> svc.add("https://right.codes/v1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("只接受 host");
        assertThatThrownBy(() -> svc.add("https://right.codes:8443"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("只接受 host");
    }

    @Test
    @DisplayName("广播 add/remove 事件应用到本地缓存，tenantScope 扩展位可解析")
    void remoteEventsApply() throws Exception {
        applyRemote("add:gateway.example.com|tenant-a");
        assertThat(svc.allowedEntries())
            .containsExactly(new ByokAllowlistService.AllowlistEntry("gateway.example.com", "tenant-a"));

        applyRemote("remove:gateway.example.com|tenant-a");
        assertThat(svc.allowedEntries()).isEmpty();
    }

    private void applyRemote(String payload) throws Exception {
        Method m = ByokAllowlistService.class.getDeclaredMethod("applyRemoteEvent", String.class);
        m.setAccessible(true);
        m.invoke(svc, payload);
    }

    private static InetAddress ip(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
