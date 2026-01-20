package io.aster.policy.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

/**
 * Redis Testcontainer 资源管理器
 *
 * 为测试环境提供 Redis 容器，支持分布式缓存失效测试。
 * 每次测试创建新容器，测试完成后自动移除。
 */
public class RedisTestResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> redisContainer;

    @Override
    public Map<String, String> start() {
        // 使用 GenericContainer 避免 RedisContainer 的 Podman 兼容问题
        redisContainer = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withReuse(false)  // 禁用复用，每次新建容器
            .withStartupTimeout(Duration.ofSeconds(120))
            .waitingFor(Wait.forListeningPort());

        redisContainer.start();

        String redisHost = "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379);

        System.out.println("[Redis Testcontainer] Started: " + redisHost);

        return Map.of(
            "quarkus.redis.hosts", redisHost
        );
    }

    @Override
    public void stop() {
        if (redisContainer != null && redisContainer.isRunning()) {
            System.out.println("[Redis Testcontainer] Stopping");
            redisContainer.stop();
        }
    }
}
