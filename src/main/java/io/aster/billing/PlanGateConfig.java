package io.aster.billing;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

/**
 * 跨服务 plan 查询配置
 *
 * aster-api 通过此配置访问 aster-cloud 的内部接口，获取租户当前订阅档位，
 * 用于审批流等需要 plan gate 的场景。
 *
 * 详见 aster-deploy/docs/pm/06-cross-service-plan-gate.md
 */
@ConfigMapping(prefix = "aster.plan-gate")
public interface PlanGateConfig {

    /** 是否启用 plan gate（关闭时所有调用直接 fail-open，按 Pro 处理） */
    @WithDefault("false")
    boolean enabled();

    /** aster-cloud 内部接口 base URL */
    @WithDefault("http://aster-cloud:3000")
    String cloudInternalUrl();

    /** HMAC 共享密钥；缺省时禁用签名（仅 dev/test 接受） */
    Optional<String> hmacKey();

    /** Plan 信息缓存 TTL（短让升级即时生效，长降低 cloud 压力） */
    @WithDefault("PT5M")
    Duration cacheTtl();

    /** 缓存最大条目数 */
    @WithDefault("10000")
    int cacheMaxEntries();

    /** HTTP 请求超时 */
    @WithDefault("PT2S")
    Duration requestTimeout();

    /**
     * 失败时的策略：true=fail-open（按 Pro 处理，业务不被 plan 系统拖死）
     * 仅在确认审计/合规要求时改为 false
     */
    @WithDefault("true")
    boolean failOpen();
}
