package io.aster.policy.scheduler;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * 后台 {@code @Scheduled} 任务的统一跳过判定（issue #116）。
 *
 * <p>默认测试 profile 已用 {@code quarkus.scheduler.enabled=false} 全局关闭调度器；但少数
 * 集成测试（如 Timer IT）需要 <b>特定</b> 调度器后台触发，会用 {@code @TestProfile} 把
 * {@code quarkus.scheduler.enabled} 重新设为 true——这会**连带打开全部**调度器，其余无关的
 * 后台任务（异常检测/清理/快照对账等）也随之触发，污染测试断言或与并行测试竞争。
 *
 * <p>本谓词让「非该 IT 所需」的后台调度器读一个统一开关 {@code aster.scheduler.background.enabled}
 * （默认 true=生产照常跑）。需要精细控制的 IT 在其 profile 里把它设为 false，即可只保留自己
 * 依赖的调度器（该调度器不挂本谓词），其余挂谓词的一律跳过。
 *
 * <p>生产/默认环境开关为 true → {@code test(...)} 返回 false（不跳过）→ 任务正常执行，
 * 每次触发仅多一次 boolean 判断，开销可忽略。用 Quarkus 官方 {@code Scheduled.SkipPredicate}
 * 机制而非自研，符合生态复用优先。
 */
@ApplicationScoped
public class BackgroundSchedulerSkipPredicate implements Scheduled.SkipPredicate {

    @ConfigProperty(name = "aster.scheduler.background.enabled", defaultValue = "true")
    boolean backgroundEnabled;

    @Override
    public boolean test(ScheduledExecution execution) {
        // enabled=true → 不跳过（正常执行）；enabled=false → 跳过。
        return !backgroundEnabled;
    }
}
