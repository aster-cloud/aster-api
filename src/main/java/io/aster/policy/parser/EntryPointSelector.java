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
        List<String> candidates = ruleNames == null ? List.of() : List.copyOf(ruleNames);
        String explicit = trimToNull(functionName);

        if (explicit != null) {
            if ("evaluate".equals(explicit) && legacyEvaluateSentinel) {
                return auto(candidates, "legacy-evaluate-sentinel");
            }
            if (candidates.contains(explicit)) {
                return new Selected(explicit, "explicit");
            }
            return new NotFound(explicit, candidates);
        }

        return auto(candidates, "unspecified");
    }

    private static Selection auto(List<String> ruleNames, String reason) {
        if (ruleNames.isEmpty()) {
            return new NoRule();
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
