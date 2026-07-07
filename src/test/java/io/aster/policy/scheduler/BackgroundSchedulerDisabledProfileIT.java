package io.aster.policy.scheduler;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #116 集成保护测试：验证「scheduler 全局启用 + 后台调度器开关关闭」的组合下，
 * 挂了 {@link BackgroundSchedulerSkipPredicate} 的后台调度器会被跳过。
 *
 * <p>这是 Codex 审查建议的回归防护：防止未来有人误把 timer poller 挂上谓词、或新增后台
 * {@code @Scheduled} 忘记归类时静默改变隔离行为。这里直接断言注入的谓词在该 profile 下
 * 判定为「跳过」，即 Timer IT 那类 profile（scheduler.enabled=true + background.enabled=false）
 * 能正确静默后台调度器。timer poller 未挂谓词，不受影响（由 Timer*IT 的功能回归覆盖）。
 */
@QuarkusTest
@TestProfile(BackgroundSchedulerDisabledProfileIT.SchedulerOnBackgroundOffProfile.class)
class BackgroundSchedulerDisabledProfileIT {

    @Inject
    BackgroundSchedulerSkipPredicate predicate;

    @Test
    void backgroundSchedulersSkippedWhenToggleOff() {
        // 该 profile 下 aster.scheduler.background.enabled=false → 谓词判定跳过。
        assertTrue(predicate.test(null),
            "scheduler.enabled=true 但 background.enabled=false 时，挂谓词的后台调度器应被跳过");
    }

    /** 模拟 Timer IT 那类 profile：全局开 scheduler，但关掉后台调度器。 */
    public static class SchedulerOnBackgroundOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.scheduler.enabled", "true",
                "aster.scheduler.background.enabled", "false"
            );
        }
    }
}
