package io.aster.policy.parser;

import aster.core.identifier.IdentifierIndex;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.lexicon.SemanticTokenKind;

import java.util.ArrayList;
import java.util.HashSet;
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

    /**
     * 校验器版本（ADR 0022 §11.5 H6）：进 source envelope 的工具链身份。
     * 白名单/多词规则/不遮蔽/碰撞校验等任一规则变更时**必须 bump**，使旧版本 envelope
     * 与新校验逻辑可区分（"这条规则当时是用哪版校验规则接受的"可审计）。
     */
    public static final String VERSION = "1";

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

    /** 兼容重载：不带领域词汇索引。 */
    public static Result validate(Map<SemanticTokenKind, List<String>> aliasSet, String locale) {
        return validate(aliasSet, locale, null);
    }

    /**
     * 校验用户 aliasSet（针对给定 locale 的基础 lexicon + 可选领域词汇索引）。
     *
     * <p>校验完整识别命名空间（Codex 复核修正）：base 规范拼写 + base 别名 + 用户别名互不
     * 遮蔽 + 不撞领域词汇标识符。所有比较走统一归一（trim + 折叠空白 + lowercase ROOT）。
     *
     * @param aliasSet        用户别名，null/空视为合法（无别名）
     * @param locale          基础语言代码（取规范拼写/别名做不遮蔽校验）
     * @param identifierIndex 领域词汇索引，null 表示不做别名↔标识符碰撞校验
     */
    public static Result validate(Map<SemanticTokenKind, List<String>> aliasSet, String locale,
                                  IdentifierIndex identifierIndex) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return Result.ok();
        }

        List<String> errors = new ArrayList<>();
        Lexicon base = resolveBase(locale);

        // 占用集（归一后）：base 规范拼写 + base 已有别名。别名不得遮蔽其中任一。
        Set<String> reserved = new HashSet<>();
        for (String kw : base.getKeywords().values()) {
            if (kw != null && !kw.isBlank()) {
                reserved.add(normalize(kw));
            }
        }
        Map<SemanticTokenKind, List<String>> baseAliases = base.getAliases();
        if (baseAliases != null) {
            for (List<String> list : baseAliases.values()) {
                for (String a : list) {
                    if (a != null && !a.isBlank()) {
                        reserved.add(normalize(a));
                    }
                }
            }
        }

        Set<String> seenAlias = new HashSet<>();
        for (Map.Entry<SemanticTokenKind, List<String>> e : aliasSet.entrySet()) {
            SemanticTokenKind kind = e.getKey();

            // 铁律 1：白名单（deny-by-default）
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
                String norm = normalize(alias);

                // 铁律 0：提交值必须已是规范空白形（trim + 单 ASCII 空格分词）。
                // 否则 Canonicalizer 把源码空白折叠成单空格，而 overlay 注入的 raw 别名
                // （如 "scaled  by" 双空格）做 translation key 匹配不到 → 校验过但编译不生效。
                // 要求 alias == normalize(alias)，保证注入值即匹配值（可审计、无隐藏分歧）。
                if (!alias.equals(norm)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）含非规范空白/大小写；"
                        + "请提交规范形 '" + norm + "'（前后无空格、词间单个空格、小写）");
                    continue;
                }

                // 铁律 2：仅多词（按归一后的真实分词，≥2 个非空 token）
                if (norm.indexOf(' ') < 0) {
                    errors.add("别名 '" + alias + "'（" + kind + "）必须是多词短语；"
                        + "单词别名会占用标识符命名空间，破坏用户空间");
                }
                // 不遮蔽 base 规范拼写 / base 别名
                if (reserved.contains(norm)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）与某规范关键词或已有别名同形，禁止遮蔽");
                }
                // 用户别名间不重复（跨 kind）
                if (!seenAlias.add(norm)) {
                    errors.add("别名 '" + alias + "' 在多个 kind 间重复定义");
                }
                // 不撞领域词汇标识符（关键词翻译先于标识符翻译 → 别名会抢赢用户字段名）
                if (identifierIndex != null && identifierIndex.hasMapping(norm)) {
                    errors.add("别名 '" + alias + "'（" + kind + "）与领域词汇标识符同形，"
                        + "会让关键词抢赢用户标识符，禁止");
                }
            }
        }

        return errors.isEmpty() ? Result.ok() : new Result(false, errors);
    }

    /** 统一归一：trim + 折叠所有空白为单个 ASCII 空格 + lowercase(ROOT)。 */
    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 确定性序列化 aliasSet 为规范 JSON（ADR 0022 §11.5，Codex 持久化复核 High）。
     *
     * <p>kind 按枚举名排序、每个 kind 的别名列表排序、别名值归一——保证同一别名集**总是**
     * 产出逐字节相同的 JSON → 同一 source envelope（可复现、跨租户一致）。这是 createVersion
     * 应使用的 aliasSetJson 来源，杜绝"同语义别名集因 key 顺序/空白不同产生不同 envelope"。
     * 空/null → null（无别名）。
     */
    public static String canonicalJson(Map<SemanticTokenKind, List<String>> aliasSet) {
        if (aliasSet == null || aliasSet.isEmpty()) {
            return null;
        }
        // TreeMap 按 kind 枚举名排序；每个列表归一+排序+去重。
        java.util.TreeMap<String, List<String>> sorted = new java.util.TreeMap<>();
        for (Map.Entry<SemanticTokenKind, List<String>> e : aliasSet.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            java.util.TreeSet<String> vals = new java.util.TreeSet<>();
            for (String a : e.getValue()) {
                if (a != null && !a.isBlank()) {
                    vals.add(normalize(a));
                }
            }
            if (!vals.isEmpty()) {
                sorted.put(e.getKey().name(), new ArrayList<>(vals));
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        try {
            // Jackson 对 TreeMap 保持 key 顺序；值已是有序 List。
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize aliasSet", e);
        }
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
        // 与 InProcessCnlParser.getLexiconForLocale 一致的前缀 fallback（避免校验用错 locale）
        if (norm.startsWith("zh") && registry.has("zh-cn")) {
            return registry.getOrThrow("zh-cn");
        }
        if (norm.startsWith("de") && registry.has("de-de")) {
            return registry.getOrThrow("de-de");
        }
        return registry.getDefault();
    }
}
