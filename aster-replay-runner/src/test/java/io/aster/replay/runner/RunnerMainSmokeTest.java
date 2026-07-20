package io.aster.replay.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 骨架冒烟：类可加载，mainClass 存在。 */
class RunnerMainSmokeTest {
    @Test
    void mainClassLoads() throws Exception {
        Class<?> c = Class.forName("io.aster.replay.runner.RunnerMain");
        assertNotNull(c.getDeclaredMethod("main", String[].class));
    }
}
