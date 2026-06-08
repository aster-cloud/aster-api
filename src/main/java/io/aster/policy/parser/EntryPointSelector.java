package io.aster.policy.parser;

import java.util.List;

/**
 * CNL 执行入口选择器。
 *
 * <p>只负责入口语义判定，不解析、不执行、无副作用，便于独立单测。
 */
public final class EntryPointSelector {

    private EntryPointSelector() {
    }

    public sealed interface Selection permits Selected, Ambiguous, NotFound, NoRule {
    }

    public record Selected(String function, String reason) implements Selection {
    }

    public record Ambiguous(List<String> candidates) implements Selection {
    }

    public record NotFound(String requested, List<String> candidates) implements Selection {
    }

    public record NoRule() implements Selection {
    }

    public static Selection select(String functionName, List<String> ruleNames, boolean legacyEvaluateSentinel) {
        return select(functionName, ruleNames, null, legacyEvaluateSentinel);
    }

    /**
     * 入口选择（ADR 0015 阶段2）。优先级：显式 functionName &gt; @entry 注解 &gt; 单 Rule &gt; 诊断。
     *
     * @param functionName 调用方显式指定的函数名（可空）
     * @param ruleNames 模块全部 Rule 名（声明顺序）
     * @param annotatedEntry 带 @entry 注解的入口函数名（可空，唯一性由 core 校验）
     * @param legacyEvaluateSentinel 是否把显式 "evaluate" 视为历史自动入口哨兵
     */
    public static Selection select(
            String functionName, List<String> ruleNames, String annotatedEntry, boolean legacyEvaluateSentinel) {
        List<String> candidates = ruleNames == null ? List.of() : List.copyOf(ruleNames);
        String explicit = trimToNull(functionName);

        if (explicit != null) {
            if ("evaluate".equals(explicit) && legacyEvaluateSentinel) {
                return auto(candidates, annotatedEntry, "legacy-evaluate-sentinel");
            }
            if (candidates.contains(explicit)) {
                return new Selected(explicit, "explicit");
            }
            return new NotFound(explicit, candidates);
        }

        return auto(candidates, annotatedEntry, "unspecified");
    }

    private static Selection auto(List<String> ruleNames, String annotatedEntry, String reason) {
        if (ruleNames.isEmpty()) {
            return new NoRule();
        }
        // @entry 注解优先于「声明顺序/数量」启发式
        String entry = trimToNull(annotatedEntry);
        if (entry != null && ruleNames.contains(entry)) {
            return new Selected(entry, "entry-annotation");
        }
        if (ruleNames.size() == 1) {
            return new Selected(ruleNames.get(0), reason);
        }
        return new Ambiguous(ruleNames);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
