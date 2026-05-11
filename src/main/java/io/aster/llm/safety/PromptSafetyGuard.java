package io.aster.llm.safety;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 提示词安全守门 — 把 PromptInjectionDetector + PromptScopeFilter 串成一道关
 *
 * 顺序：先黑名单（jailbreak）后白名单（话题）— 黑名单命中直接拒绝并标记
 * jailbreak_attempt=true 给后续 anomaly Signal 4 累计计数。
 */
@ApplicationScoped
public class PromptSafetyGuard {

    @Inject
    PromptInjectionDetector injectionDetector;

    @Inject
    PromptScopeFilter scopeFilter;

    public SafetyVerdict guard(String prompt, PromptScopeFilter.Strictness strictness) {
        SafetyVerdict inj = injectionDetector.detect(prompt);
        if (inj.blocked()) {
            return inj;
        }
        return scopeFilter.check(prompt, strictness);
    }
}
