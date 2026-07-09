package io.aster.security.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfGuardTest {

    private SsrfGuard guard(InetAddress... addresses) {
        return new SsrfGuard(host -> List.of(addresses));
    }

    private static InetAddress ip(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static InetAddress mapped169() {
        try {
            return InetAddress.getByAddress(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
                (byte) 169, (byte) 254, (byte) 169, (byte) 254
            });
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    @DisplayName("公网 HTTPS host → 规范化后放行")
    void allowsPublicHttpsHost() {
        ValidatedEndpoint endpoint = guard(ip("93.184.216.34"))
            .validate("https://Example.COM./v1");

        assertThat(endpoint.canonicalHost()).isEqualTo("example.com");
        assertThat(endpoint.port()).isEqualTo(443);
        assertThat(endpoint.pathPrefix()).isEqualTo("/v1");
        assertThat(endpoint.ssl()).isTrue();
        assertThat(endpoint.pinnedIp()).isPresent();
    }

    @Test
    @DisplayName("禁止 http scheme")
    void rejectsHttp() {
        assertThatThrownBy(() -> guard(ip("93.184.216.34")).validate("http://example.com"))
            .isInstanceOf(SsrfViolation.class)
            .hasMessageContaining("https");
    }

    @Test
    @DisplayName("禁止 userinfo/query/fragment")
    void rejectsUrlPartsThatCanHideTargets() {
        SsrfGuard guard = guard(ip("93.184.216.34"));
        assertThatThrownBy(() -> guard.validate("https://user:pass@example.com"))
            .isInstanceOf(SsrfViolation.class);
        assertThatThrownBy(() -> guard.validate("https://example.com?x=1"))
            .isInstanceOf(SsrfViolation.class);
        assertThatThrownBy(() -> guard.validate("https://example.com#frag"))
            .isInstanceOf(SsrfViolation.class);
    }

    @Test
    @DisplayName("禁止云元数据与 loopback/link-local/private IPv4")
    void rejectsForbiddenIpv4Ranges() {
        assertForbidden("169.254.169.254");
        assertForbidden("169.254.170.2");
        assertForbidden("127.0.0.1");
        assertForbidden("10.1.2.3");
        assertForbidden("172.16.0.1");
        assertForbidden("192.168.1.1");
        assertForbidden("100.64.0.1");
    }

    @Test
    @DisplayName("禁止 IPv6 loopback/link-local/ULA 与 IPv4-mapped IPv6")
    void rejectsForbiddenIpv6Ranges() {
        assertForbidden("::1");
        assertForbidden("fe80::1");
        assertForbidden("fc00::1");
        assertThatThrownBy(() -> guard(mapped169()).validate("https://example.com"))
            .isInstanceOf(SsrfViolation.class);
    }

    @Test
    @DisplayName("DNS 返回公私混合 IP → 全拒，防 rebinding/多 A 记录混淆")
    void rejectsMixedPublicAndPrivateDnsResults() {
        assertThatThrownBy(() -> guard(ip("93.184.216.34"), ip("10.0.0.10"))
            .validate("https://example.com"))
            .isInstanceOf(SsrfViolation.class);
    }

    private static void assertForbidden(String address) {
        assertThatThrownBy(() -> new SsrfGuard(host -> List.of(ip(address))).validate("https://example.com"))
            .isInstanceOf(SsrfViolation.class)
            .hasMessageContaining("禁止");
    }
}
