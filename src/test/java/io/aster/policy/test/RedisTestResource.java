package io.aster.policy.test;

import com.redis.testcontainers.RedisContainer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Redis Testcontainer 资源管理器
 *
 * 为测试环境提供 Redis 容器，支持分布式缓存失效测试
 */
public class RedisTestResource implements QuarkusTestResourceLifecycleManager {

    private RedisContainer redisContainer;

    @Override
    public Map<String, String> start() {
        // 使用官方 Redis 镜像，兼容 Docker 和 Podman
        redisContainer = new RedisContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);  // 复用容器提高测试速度

        redisContainer.start();

        String redisHost = "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379);

        System.out.println("[Redis Testcontainer] Started: " + redisHost);

        // 配置 Quarkus Redis 连接
        // 移除 value-type 显式配置，让 Quarkus 自动推导类型（避免 ClassCastException）
        return Map.of(
            "quarkus.redis.hosts", redisHost
        );
    }

    @Override
    public void stop() {
        if (redisContainer != null) {
            System.out.println("[Redis Testcontainer] Stopping");
            redisContainer.stop();
        }
    }
}
