package io.aster.llm.safety;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 越狱 / prompt-injection 检测的 v1 实现：基于关键词 regex
 *
 * 8 条规则与 aster-cloud TypeScript 版同源
 * （aster-cloud/src/lib/ai-content-safety.ts 的 RegexInjectionDetector）。
 *
 * 漏检不阻塞业务：由 anomaly detection Signal 4（24h 内累计 ≥3 次）兜底自动封禁。
 */
@ApplicationScoped
public class RegexInjectionDetector implements PromptInjectionDetector {

    private record Rule(String id, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
        new Rule(
            "ignore-previous",
            Pattern.compile(
                "\\b(ignore|disregard|forget)\\s+(all\\s+|the\\s+|your\\s+|previous\\s+|prior\\s+|above\\s+)+"
                    + "(instructions?|prompts?|rules?|system\\s+prompt)\\b",
                Pattern.CASE_INSENSITIVE
            )
        ),
        new Rule(
            "system-prompt-leak",
            Pattern.compile(
                "\\b(reveal|show|print|repeat|leak|tell\\s+me)\\s+(your\\s+|the\\s+)?"
                    + "(system\\s+prompt|initial\\s+instructions?|hidden\\s+instructions?)\\b",
                Pattern.CASE_INSENSITIVE
            )
        ),
        new Rule(
            "dan-mode",
            Pattern.compile("\\b(DAN|do\\s+anything\\s+now)\\s+(mode|prompt)?\\b", Pattern.CASE_INSENSITIVE)
        ),
        new Rule(
            "developer-mode",
            Pattern.compile(
                "\\b(enter|enable|activate)\\s+(developer|jailbroken|unrestricted|god)\\s+mode\\b",
                Pattern.CASE_INSENSITIVE
            )
        ),
        new Rule(
            "pretend-no-rules",
            Pattern.compile(
                "\\b(pretend|imagine|act\\s+as\\s+if)\\s+"
                    + "(you\\s+(are|have)|there\\s+(are|is))\\s+"
                    + "(no|without\\s+any?)\\s+(rules?|restrictions?|filters?|guidelines?)\\b",
                Pattern.CASE_INSENSITIVE
            )
        ),
        new Rule(
            "role-override",
            Pattern.compile(
                "\\byou\\s+are\\s+now\\s+(an?\\s+)?"
                    + "(unrestricted|jailbroken|evil|amoral|uncensored|unfiltered)\\b",
                Pattern.CASE_INSENSITIVE
            )
        ),
        new Rule(
            "grandma-exploit",
            Pattern.compile(
                "\\bmy\\s+(dead\\s+)?(grand(ma|mother)|grandpa|grandfather)\\s+"
                    + "(used\\s+to\\s+)?(tell|read|recite|whisper)",
                Pattern.CASE_INSENSITIVE
            )
        ),
        new Rule(
            "override-safety",
            Pattern.compile(
                "\\b(override|bypass|circumvent|disable)\\s+"
                    + "(safety|content)\\s+(filters?|policy|policies|guidelines?)\\b",
                Pattern.CASE_INSENSITIVE
            )
        )
    );

    @Override
    public SafetyVerdict detect(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return SafetyVerdict.allow();
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(prompt).find()) {
                return SafetyVerdict.block(rule.id(), "请求被内容安全策略拦截");
            }
        }
        return SafetyVerdict.allow();
    }
}
