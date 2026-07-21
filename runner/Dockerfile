# runner 最小 arm64 stock-JRE 镜像。
# ★集群节点 ARM64（OCI Ampere），必须真 arm64（承 Dockerfile.jvm arch 断言，防 QEMU 误标）。
# stock Zulu JRE（非 GraalVM JDK）——Truffle 走解释器模式（engine.WarnInterpreterOnly=false 已在 :replay 内设）。
FROM --platform=$TARGETPLATFORM azul/zulu-openjdk-alpine:25-jre

ARG TARGETARCH
# arch 断言：防 buildx/QEMU 跨构建静默产 amd64 却标 arm64 → 节点 exec format error。
RUN set -eux; \
    machine="$(uname -m)"; \
    case "$TARGETARCH" in \
        arm64) expect="aarch64" ;; \
        amd64) expect="x86_64" ;; \
        *) echo "unsupported TARGETARCH=$TARGETARCH"; exit 1 ;; \
    esac; \
    [ "$machine" = "$expect" ] || { echo "arch mismatch: uname=$machine expect=$expect (TARGETARCH=$TARGETARCH)"; exit 1; }

WORKDIR /app
# application installDist 产物：bin/ 启动脚本 + lib/*.jar（locale 四 jar 物理分开，SPI 零合并）。
COPY build/install/runner/ /app/

# 最小权限：非 root。
RUN addgroup -S runner && adduser -S runner -G runner
USER runner

# runner 读 stdin JSON → stdout envelope。启动脚本名 = application project name（=runner）。
ENTRYPOINT ["/app/bin/runner"]
