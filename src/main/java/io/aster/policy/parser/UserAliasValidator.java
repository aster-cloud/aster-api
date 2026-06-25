package io.aster.policy.parser;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户自定义别名校验（ADR 0022 §11.5，方案 D）。
 *
 * <p>用户提交的 aliasSet 必须先过本校验才能进入编译/版本快照。本校验堵住 Codex 对抗复核
 * 挖出的 High-3 社会工程攻击面：别名映射到哪个 kind 是用户定的，若放任用户把 {@code approve}
 * 映射到 {@code RETURN} 或把误导短语映射到控制流/授权/拒绝类 kind，审批者看到的是别名源码、
 * 实际批准的却是归一后的语义。
 *
 * <p>两条铁律：
 * <ol>
 *   <li><b>白名单（非黑名单）</b>：只有低风险的运算符/比较类 kind 允许用户自定义别名。
 *       用 allowlist 而非 denylist：新增 kind 默认**不**可被用户别名（deny-by-default），
 *       避免"加了新 kind 忘记拉黑"的静默放行。</li>
 *   <li><b>仅多词</b>：单词别名占用标识符命名空间（用户拿同名词作字段/参数会被当关键词，
 *       破坏用户空间）。多词别名（含空格）天然安全：仅相邻序列匹配，子词仍可作标识符。</li>
 * </ol>
 *
 * <p>另复用 core {@code LexiconValidator} 的不遮蔽语义：别名不得撞规范拼写/其它别名。
 */
public final class UserAliasValidator {

    private UserAliasValidator() {}

    /**
     * 允许用户自定义别名的 kind 白名单（低风险运算符/比较）。
     *
     * <p>**故意排除**：声明（FUNC_TO/TYPE_DEF/MODULE_DECL）、控制流（IF/OTHERWISE/MATCH/
     * WHEN/FOR_EACH）、返回（RETURN/RESULT_IS）、导入（IMPORT*）、效果/外部调用（IO/CPU/
     * AWAIT/WORKFLOW/STEP/…）、逻辑（AND/OR/NOT）、布尔/类型字面量。这些 kind 的别名滥用
     * 会误导审批者或改变可读语义。
     */
    private static final Set<SemanticTokenKind> ALLOWED_KINDS = Set.of(
        SemanticTokenKind.PLUS,
        SemanticTokenKind.MINUS_WORD,
        SemanticTokenKind.TIMES,
        SemanticTokenKind.DIVIDED_BY,
        SemanticTokenKind.INTEGER_DIVIDED_BY,
        SemanticTokenKind.MODULO,
        SemanticTokenKind.LESS_THAN,
        SemanticTokenKind.GREATER_THAN,
        SemanticTokenKind.EQUALS_TO,
        SemanticTokenKind.AT_LEAST,
        SemanticTokenKind.AT_MOST
    );

    /** 校验结果：valid + 错误清单。 */
    public record Result(boolean valid, List<String> errors) {
        public static Result ok() {
            return new Result(true, List.of());
        }
    }

    /**
     * 校验用户 aliasSet（针对给定 locale 的基础 lexicon）。
     *
     * @param aliasSet 用户别名，null/空视为合法（无别名）
     * @param locale   基础语言代码（用于取规范拼写做不遮蔽校验）
     */
    public static Result validate(Map<SemanticTokenKind, List<String>> aliasSet, String locale) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return Result.ok();
        }

        List<String> errors = new ArrayList<>();

        // 取基础 lexicon 的规范拼写集（lowercase）用于不遮蔽校验
        Lexicon base = resolveBase(locale);
        Set<String> canonicalLower = new java.util.HashSet<>();
        for (String kw : base.getKeywords().values()) {
            if (kw != null && !kw.isBlank()) {
                canonicalLower.add(kw.toLowerCase(Locale.ROOT));
            }
        }

        Set<String> seenAlias = new java.util.HashSet<>();
        for (Map.Entry<SemanticTokenKind, List<String>> e : aliasSet.entrySet()) {
            SemanticTokenKind kind = e.getKey();

            // 铁律 1：白名单
            if (!ALLOWED_KINDS.contains(kind)) {
                errors.add("不允许为 " + kind + " 自定义别名（仅低风险运算符/比较类可自定义，"
                    + "防止误导审批的语义滥用）");
                continue;
            }

            List<String> aliases = e.getValue();
            if (aliases == null || aliases.isEmpty()) {
                continue;
            }
            for (String alias : aliases) {
                if (alias == null || alias.isBlank()) {
                    errors.add(kind + " 的别名不能为空");
                    continue;
                }
                String trimmed = alias.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);

                // 铁律 2：仅多词（含空格）
                if (!trimmed.contains(" ")) {
                    errors.add("别名 '" + alias + "'（" + kind + "）必须是多词短语；"
                        + "单词别名会占用标识符命名空间，破坏用户空间");
                }
                // 不遮蔽规范拼写
                if (canonicalLower.contains(lower)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）与某规范关键词同形，禁止遮蔽");
                }
                // 别名间不重复
                if (!seenAlias.add(lower)) {
                    errors.add("别名 '" + alias + "' 在多个 kind 间重复定义");
                }
            }
        }

        return errors.isEmpty() ? Result.ok() : new Result(false, errors);
    }

    private static Lexicon resolveBase(String locale) {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        if (locale == null || locale.isBlank()) {
            return registry.getDefault();
        }
        String norm = locale.toLowerCase(Locale.ROOT).replace('_', '-');
        if (registry.has(norm)) {
            return registry.getOrThrow(norm);
        }
        return registry.getDefault();
    }
}
