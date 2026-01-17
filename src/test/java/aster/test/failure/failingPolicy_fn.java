package aster.test.failure;

/**
 * 测试专用策略：始终抛出异常以模拟执行失败。
 */
public final class failingPolicy_fn {

    private failingPolicy_fn() {
        // 禁止实例化
    }

    public static Integer failingPolicy(Integer delay) {
        throw new IllegalStateException("测试策略故意失败");
    }
}
