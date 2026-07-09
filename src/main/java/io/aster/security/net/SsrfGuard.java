package io.aster.security.net;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigInteger;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 出站 URL SSRF 基础防线。
 *
 * <p>设计边界：CIDR deny 表可以 static final；DNS 结果绝不 static/cache 到类初始化，保证
 * GraalVM native 下仍在运行期解析。GA 的 BYOK 自定义 Provider URL 仍必须命中管理员 allowlist，
 * 本 guard 负责防配置错误和未来任意 URL beta 的基础设施。
 */
@ApplicationScoped
public class SsrfGuard {

    private static final List<IpRange> DENY_RANGES = List.of(
        // IPv4：私网/loopback/link-local/元数据/CGNAT/文档/保留/组播/广播。
        IpRange.v4("0.0.0.0", 8),
        IpRange.v4("10.0.0.0", 8),
        IpRange.v4("100.64.0.0", 10),
        IpRange.v4("127.0.0.0", 8),
        IpRange.v4("169.254.0.0", 16),
        IpRange.v4("172.16.0.0", 12),
        IpRange.v4("192.0.0.0", 24),
        IpRange.v4("192.0.2.0", 24),
        IpRange.v4("192.168.0.0", 16),
        IpRange.v4("198.18.0.0", 15),
        IpRange.v4("198.51.100.0", 24),
        IpRange.v4("203.0.113.0", 24),
        IpRange.v4("224.0.0.0", 4),
        IpRange.v4("240.0.0.0", 4),
        IpRange.v4("255.255.255.255", 32),
        // IPv6：unspecified/loopback/NAT64/ULA/link-local/site-local/doc/6to4/multicast。
        // IPv4-mapped IPv6 先在 normalizeMappedIpv4() 还原成 IPv4，再走 IPv4 deny-list。
        IpRange.v6("::", 128),
        IpRange.v6("::1", 128),
        IpRange.v6("64:ff9b::", 96),
        IpRange.v6("100::", 64),
        IpRange.v6("2001:db8::", 32),
        IpRange.v6("2002::", 16),
        IpRange.v6("fc00::", 7),
        IpRange.v6("fe80::", 10),
        IpRange.v6("fec0::", 10),
        IpRange.v6("ff00::", 8)
    );

    private final Function<String, List<InetAddress>> resolver;

    public SsrfGuard() {
        this(SsrfGuard::resolveWithJdk);
    }

    public SsrfGuard(Function<String, List<InetAddress>> resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * 解析并校验 HTTPS endpoint。所有 DNS 返回 IP 都必须通过 deny-list；只要混入私网/保留地址即拒绝。
     */
    public ValidatedEndpoint validate(String rawUrl) {
        URI uri = parseAndNormalize(rawUrl);
        String host = uri.getHost();
        List<InetAddress> addresses = resolveHost(host);
        for (InetAddress address : addresses) {
            rejectForbiddenIp(address);
        }
        InetAddress pinned = addresses.stream()
            .min(Comparator.comparing(InetAddress::getHostAddress))
            .orElse(null);
        return new ValidatedEndpoint(uri, host, resolvePort(uri), normalizePath(uri),
            Optional.ofNullable(pinned), true);
    }

    URI parseAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfViolation("baseUrl 为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new SsrfViolation("baseUrl 不是合法 URI", e);
        }
        if (!uri.isAbsolute()) {
            throw new SsrfViolation("baseUrl 必须是 absolute URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SsrfViolation("baseUrl 只允许 https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new SsrfViolation("baseUrl 不允许 userinfo");
        }
        if (uri.getRawQuery() != null) {
            throw new SsrfViolation("baseUrl 不允许 query");
        }
        if (uri.getRawFragment() != null) {
            throw new SsrfViolation("baseUrl 不允许 fragment");
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new SsrfViolation("baseUrl 缺少 host");
        }
        String host = canonicalizeHost(rawHost);
        int port = resolvePort(uri);
        String path = normalizePath(uri);
        try {
            return new URI("https", null, host, port == 443 ? -1 : port, path, null, null);
        } catch (Exception e) {
            throw new SsrfViolation("baseUrl 规范化失败", e);
        }
    }

    private static String canonicalizeHost(String rawHost) {
        String host = rawHost.trim();
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new SsrfViolation("baseUrl host 非法", e);
        }
    }

    private static int resolvePort(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            return 443;
        }
        if (port <= 0 || port > 65535) {
            throw new SsrfViolation("baseUrl port 非法");
        }
        return port;
    }

    private static String normalizePath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            return "";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
            throw new SsrfViolation("baseUrl path 不允许编码穿越字符");
        }
        String normalized = URI.create(path).normalize().getPath();
        if (normalized == null || normalized.isBlank() || "/".equals(normalized)) {
            return "";
        }
        if (normalized.contains("..")) {
            throw new SsrfViolation("baseUrl path 不允许穿越");
        }
        return normalized;
    }

    private List<InetAddress> resolveHost(String host) {
        List<InetAddress> addresses;
        try {
            addresses = resolver.apply(host);
        } catch (RuntimeException e) {
            throw new SsrfViolation("baseUrl host DNS 解析失败", e);
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new SsrfViolation("baseUrl host DNS 无结果");
        }
        return addresses;
    }

    private static List<InetAddress> resolveWithJdk(String host) {
        try {
            return Arrays.asList(InetAddress.getAllByName(host));
        } catch (Exception e) {
            throw new SsrfViolation("baseUrl host DNS 解析失败", e);
        }
    }

    private static void rejectForbiddenIp(InetAddress address) {
        InetAddress normalized = normalizeMappedIpv4(address);
        for (IpRange range : DENY_RANGES) {
            if (range.contains(normalized)) {
                throw new SsrfViolation("baseUrl host 解析到禁止的内网/保留 IP: " + address.getHostAddress());
            }
        }
    }

    private static InetAddress normalizeMappedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= bytes[i] == 0;
            }
            mapped &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            if (mapped) {
                byte[] v4 = Arrays.copyOfRange(bytes, 12, 16);
                try {
                    return InetAddress.getByAddress(v4);
                } catch (Exception e) {
                    throw new SsrfViolation("IPv4-mapped IPv6 解析失败", e);
                }
            }
        }
        return address;
    }

    private record IpRange(int bytes, BigInteger network, BigInteger mask) {

        static IpRange v4(String address, int prefix) {
            return of(address, prefix, 4);
        }

        static IpRange v6(String address, int prefix) {
            return of(address, prefix, 16);
        }

        static IpRange of(String address, int prefix, int bytes) {
            try {
                byte[] raw = InetAddress.getByName(address).getAddress();
                if (raw.length != bytes) {
                    throw new IllegalArgumentException("IP version mismatch");
                }
                BigInteger mask = mask(prefix, bytes);
                BigInteger network = unsigned(raw).and(mask);
                return new IpRange(bytes, network, mask);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] raw = address.getAddress();
            return raw.length == bytes && unsigned(raw).and(mask).equals(network);
        }

        private static BigInteger mask(int prefix, int bytes) {
            int bits = bytes * 8;
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("invalid prefix");
            }
            if (prefix == 0) {
                return BigInteger.ZERO;
            }
            return BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE));
        }

        private static BigInteger unsigned(byte[] raw) {
            return new BigInteger(1, HexFormat.of().parseHex(HexFormat.of().formatHex(raw)));
        }
    }
}
