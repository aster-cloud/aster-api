package aster.test.batch;

/**
 * 测试专用策略：通过休眠模拟耗时执行，用于验证批量并行。
 */
public final class slowPolicy_fn {

    private slowPolicy_fn() {
        // 禁止实例化
    }

    public static Integer slowPolicy(Integer sleepMillis) {
        if (sleepMillis == null) {
            sleepMillis = 0;
        }
        try {
            Thread.sleep(Math.max(0, sleepMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sleepMillis;
    }
}
