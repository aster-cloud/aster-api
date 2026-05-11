package io.aster.policy.telemetry;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * Mixpanel 服务端投递配置
 *
 * 用 Vert.x WebClient 直连 Mixpanel /track HTTP API，避免 mixpanel-java SDK
 * 在 GraalVM Native Image 下的反射元数据问题。
 */
@ConfigMapping(prefix = "aster.mixpanel")
public interface MixpanelConfig {

    /** 是否启用 Mixpanel 上报；生产由 ExternalSecret 注入 token 时一并启用 */
    @WithDefault("false")
    boolean enabled();

    /** Mixpanel project token；缺省时即便 enabled=true 也会被 Client 自动禁用 */
    Optional<String> token();

    /** Mixpanel 端点 base URL；测试可指向 mock server */
    @WithDefault("https://api.mixpanel.com")
    String baseUrl();

    /** 单批投递的最大事件数；达到后触发 flush */
    @WithDefault("50")
    int batchSize();

    /** 周期性 flush 间隔；即使队列未满也会定时清空 */
    @WithDefault("PT5S")
    Duration flushInterval();

    /** 队列容量上限；超过后丢弃事件并增加 dropped Counter */
    @WithDefault("5000")
    int queueCapacity();

    /** HTTP 失败时的最大重试次数（指数退避） */
    @WithDefault("3")
    int maxRetries();

    /** 连续失败次数达到此值后开路熔断；冷却期内直接丢弃 */
    @WithDefault("10")
    int circuitOpenThreshold();

    /** 熔断开路后的冷却时间 */
    @WithDefault("PT1M")
    Duration circuitCooldown();
}
