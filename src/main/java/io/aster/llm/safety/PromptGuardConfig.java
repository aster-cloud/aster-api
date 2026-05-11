package io.aster.llm.safety;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Prompt 安全守门配置
 *
 * 关键开关：
 *   - enabled: 全局启用 / 关闭（默认开）
 *   - uiBlocksFreeQuota: UI 路径前 N 次拦截不扣配额（PM 取舍：3 次容错）
 *
 * 详见 aster-deploy/docs/pm/07-ai-billing.md "提示词治理"
 */
@ConfigMapping(prefix = "aster.prompt-guard")
public interface PromptGuardConfig {

    @WithDefault("true")
    boolean enabled();

    /** UI 路径每用户 N 次拦截内不扣配额（容错），第 N+1 次起每次扣 */
    @WithDefault("3")
    int uiFreeBlocks();
}
