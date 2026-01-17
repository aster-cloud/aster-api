package io.aster.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Map;

/**
 * PostgreSQL Testcontainers 测试资源（Phase 3.4）
 *
 * 提供生产环境一致的 PostgreSQL 测试环境，解决 H2 兼容性问题。
 * 使用容器复用（reuse=true）加速重复测试。
 *
 * 镜像来源: postgres:17-alpine (官方镜像, PostgreSQL 17)
 *
 * 注意: Bitnami PostgreSQL 镜像与 Testcontainers PostgreSQLContainer 不兼容,
 *       因为 Bitnami 使用非标准文件路径和配置结构。如需使用 Bitnami 镜像，
 *       需要扩展 GenericContainer 并自定义所有配置。
 *
 * Podman 配置 (macOS AppleHV):
 *   - 创建 ~/.testcontainers.properties 文件，配置:
 *     docker.host=unix:///Users/rpang/.local/share/containers/podman/machine/podman.sock
 *     testcontainers.reuse.enable=true
 *     ryuk.container.privileged=false
 *   - 或设置环境变量: DOCKER_HOST=unix:///Users/rpang/.local/share/containers/podman/machine/podman.sock
 *   - Testcontainers 会自动检测 Podman
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true)  // 共享容器，加速测试
        .withStartupTimeout(Duration.ofSeconds(180))  // 容器启动超时（拉取镜像时需要）
        .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off");  // 测试性能优化

    @Override
    public Map<String, String> start() {
        postgres.start();
        String reactiveUrl = String.format(
            "postgresql://%s:%d/%s",
            postgres.getHost(),
            postgres.getMappedPort(5432),
            postgres.getDatabaseName()
        );
        return Map.of(
            "quarkus.datasource.jdbc.url", postgres.getJdbcUrl(),
            "quarkus.datasource.reactive.url", reactiveUrl,
            "quarkus.datasource.username", postgres.getUsername(),
            "quarkus.datasource.password", postgres.getPassword(),
            "quarkus.datasource.db-kind", "postgresql"
        );
    }

    @Override
    public void stop() {
        // 容器自动停止（reuse=true 时跳过）
    }
}
