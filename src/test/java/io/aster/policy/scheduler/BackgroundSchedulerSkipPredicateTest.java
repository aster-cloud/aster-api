package io.aster.policy.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BackgroundSchedulerSkipPredicate 单元测试（issue #116）。
 * 验证开关语义：enabled=true → 不跳过（正常执行）；enabled=false → 跳过。
 */
class BackgroundSchedulerSkipPredicateTest {

    @Test
    void skipsWhenBackgroundDisabled() {
        BackgroundSchedulerSkipPredicate predicate = new BackgroundSchedulerSkipPredicate();
        predicate.backgroundEnabled = false;
        // test() 返回 true = 跳过执行
        assertTrue(predicate.test(null), "开关关闭时应跳过后台调度器执行");
    }

    @Test
    void runsWhenBackgroundEnabled() {
        BackgroundSchedulerSkipPredicate predicate = new BackgroundSchedulerSkipPredicate();
        predicate.backgroundEnabled = true;
        // test() 返回 false = 不跳过，正常执行
        assertFalse(predicate.test(null), "开关开启（生产默认）时不应跳过");
    }
}
