# syntax=docker/dockerfile:1.7
#
# Quarkus Policy API - Native Production Image
#
# Stage 1  Node builder: 生成 policy-rules-merged.jar 及配套类文件
# Stage 2  Gradle builder: 使用 GraalVM 编译原生二进制
# Stage 3  Runtime: UBI 最小化镜像，仅携带可执行文件与运行依赖

# ============================================
# Stage 1: Node builder (emit .aster → jar)
# ============================================
FROM node:25-bookworm AS node-builder

RUN apt-get update && \
    apt-get install -y --no-install-recommends bash git python3 make g++ curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN ARCH=$(dpkg --print-architecture) && \
    if [ "$ARCH" = "amd64" ]; then \
        JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.1%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.1_8.tar.gz"; \
    elif [ "$ARCH" = "arm64" ]; then \
        JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.1%2B8/OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.1_8.tar.gz"; \
    else \
        echo "Unsupported architecture: $ARCH" && exit 1; \
    fi && \
    curl -fsSL "$JDK_URL" -o /tmp/openjdk25.tar.gz && \
    mkdir -p /usr/lib/jvm/java-25-temurin && \
    tar -xzf /tmp/openjdk25.tar.gz -C /usr/lib/jvm/java-25-temurin --strip-components=1 && \
    rm /tmp/openjdk25.tar.gz

ENV JAVA_HOME=/usr/lib/jvm/java-25-temurin
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace

COPY package.json package-lock.json tsconfig.json ./
RUN npm ci

# 复制剩余源代码（TypeScript、scripts、policies 等）
COPY . .

# 预先编译 test/generators.ts 以满足 TypeScript 中对 .js 模块的依赖
RUN npx tsc test/generators.ts --module ES2022 --target ES2022 --outDir test --declaration false --skipLibCheck

# 构建 TypeScript CLI + 通过 Gradle 生成策略 JAR 并注入 workflow 依赖
RUN npm run build && \
    chmod +x gradlew && \
    ./gradlew -g build/.gradle :quarkus-policy-api:generateAsterJar \
      --no-daemon --stacktrace \
      -Dorg.gradle.configuration-cache=false

# ============================================
# Stage 2: Gradle native builder
# ============================================
FROM ghcr.io/graalvm/native-image-community:25-ol9 AS gradle-builder
ARG UPX_VERSION=5.0.2

USER root

WORKDIR /workspace

RUN microdnf install -y unzip zip findutils curl binutils xz && microdnf clean all && \
    ARCH=$(uname -m) && \
    if [ "$ARCH" = "x86_64" ]; then UPX_ARCH="amd64_linux"; elif [ "$ARCH" = "aarch64" ]; then UPX_ARCH="arm64_linux"; else UPX_ARCH="amd64_linux"; fi && \
    curl -fsSL https://github.com/upx/upx/releases/download/v${UPX_VERSION}/upx-${UPX_VERSION}-${UPX_ARCH}.tar.xz -o /tmp/upx.tar.xz && \
    tar -xf /tmp/upx.tar.xz -C /tmp && \
    mv /tmp/upx-${UPX_VERSION}-${UPX_ARCH}/upx /usr/local/bin/upx && \
    chmod +x /usr/local/bin/upx && \
    rm -rf /tmp/upx.tar.xz /tmp/upx-${UPX_VERSION}-${UPX_ARCH}

ENV JAVA_HOME=/usr/lib64/graalvm/graalvm-community-java25
ENV GRAALVM_HOME=/usr/lib64/graalvm/graalvm-community-java25
ENV PATH="${JAVA_HOME}/bin:${PATH}"

ENV GRADLE_OPTS="-Dorg.gradle.java.installations.paths=${JAVA_HOME}"
ENV ORG_GRADLE_PROJECT_org_gradle_java_installations_paths=${JAVA_HOME}
ENV ORG_GRADLE_PROJECT_org_gradle_java_installations_auto_download=false
ENV ORG_GRADLE_PROJECT_org_gradle_java_installations_auto_detect=false
ENV ORG_GRADLE_PROJECT_org_gradle_configuration_cache=false

COPY . .

# 复用 Node 阶段生成的策略产物，避免在 Gradle 阶段重复 npm 步骤
# jvm-classes 现在位于 build/aster-out/jvm-classes（隔离目录解决并行构建竞态条件）
COPY --from=node-builder /workspace/build/aster-out ./build/aster-out

ENV SKIP_GENERATE_ASTER_JAR=true

RUN chmod +x gradlew && \
    ./gradlew :quarkus-policy-api:build \
      -Dquarkus.package.type=native \
      -x test \
      -Dorg.gradle.configuration-cache=false \
      -Dquarkus.native.additional-build-args="--no-fallback,-H:+StaticExecutableWithDynamicLibC,-H:Optimize=2,-H:-IncludeAllTimeZones,-H:+RemoveUnusedSymbols,--initialize-at-run-time=io.micrometer.common.util.internal.logging.Slf4JLoggerFactory" \
      --no-daemon --stacktrace

RUN strip -s /workspace/quarkus-policy-api/build/quarkus-policy-api-unspecified-runner
RUN upx --best --lzma /workspace/quarkus-policy-api/build/quarkus-policy-api-unspecified-runner >/dev/null

# ============================================
# Stage 3: Minimal runtime image
# ============================================
FROM registry.access.redhat.com/ubi9/ubi-micro:9.7

ENV APP_HOME=/app
WORKDIR ${APP_HOME}

RUN mkdir -p /tmp && chmod 1777 /tmp

COPY --from=gradle-builder --chown=65534:65534 /workspace/quarkus-policy-api/build/quarkus-policy-api-unspecified-runner ${APP_HOME}/policy-api

USER 65534:65534

EXPOSE 8080

ENTRYPOINT ["/app/policy-api"]

LABEL maintainer="Aster Lang Team" \
      org.opencontainers.image.source="https://github.com/wontlost-ltd/aster-lang" \
      org.opencontainers.image.title="Aster Policy API Native" \
      org.opencontainers.image.version="0.2.0" \
      io.aster.binary.size.target="<120MB" \
      io.aster.startup.time.target="<150ms"
