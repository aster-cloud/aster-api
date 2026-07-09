package io.aster.security.net;

import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;

/**
 * 已通过 SSRF 基础校验的出站 endpoint。
 *
 * <p>GA allowlist 仅使用规范化后的 host/port/path；{@code pinnedIp} 是任意 URL beta 的钩子，
 * 用于后续实现「校验 IP == 实际连接 IP」的 pin-IP 连接。本轮不把 pinned IP 接入 Vert.x 出站。
 */
public record ValidatedEndpoint(
    URI originalUri,
    String canonicalHost,
    int port,
    String pathPrefix,
    Optional<InetAddress> pinnedIp,
    boolean ssl
) {
}
