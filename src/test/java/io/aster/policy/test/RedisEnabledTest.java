package io.aster.policy.test;

import io.quarkus.test.common.QuarkusTestResource;
import java.lang.annotation.*;

/**
 * 标注测试类需要 Redis Testcontainer 支持
 *
 * 使用方式：
 * <pre>
 * @QuarkusTest
 * @RedisEnabledTest
 * public class MyDistributedCacheTest {
 *     // 测试代码可以访问 Redis
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@QuarkusTestResource(RedisTestResource.class)
public @interface RedisEnabledTest {
}
