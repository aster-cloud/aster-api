package io.aster.policy.parser;

import aster.core.lexicon.CanonicalizationConfig;
import aster.core.lexicon.ErrorMessages;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.PunctuationConfig;
import aster.core.lexicon.SemanticTokenKind;

import java.util.List;
import java.util.Map;

/**
 * 别名覆盖装饰器（ADR 0022 方案 D）。
 *
 * <p>把一个基础 lexicon（en-US/zh-CN/…）与一组**用户自定义别名**（aliasSet）组合：
 * 除 {@link #getAliases()} 返回 aliasSet 外，其余全部透传基础 lexicon。
 *
 * <p>设计原则（与 ADR 0022 §3 核心不变式一致）：别名只在**识别侧**。Canonicalizer
 * 读 {@code getAliases()} 把别名归一成规范拼写后再进下游 → 用别名与规范拼写写的同一
 * 逻辑产出**同一 Core IR**。aliasSet 为空时行为与基础 lexicon 逐字节相同（向后兼容）。
 *
 * <p>方案 D 形态：aliasSet 来自策略版本快照（不可变、随版本固化、进哈希覆盖），编译期
 * 注入；runtime 不涉及（{@code /evaluate} 跑已编译的 Core IR）。
 */
public final class AliasOverlayLexicon implements Lexicon {

    private final Lexicon base;
    private final Map<SemanticTokenKind, List<String>> aliases;

    private AliasOverlayLexicon(Lexicon base, Map<SemanticTokenKind, List<String>> aliases) {
        this.base = base;
        this.aliases = Map.copyOf(aliases);
    }

    /**
     * 用 aliasSet 包裹基础 lexicon。aliasSet 为空/null 时直接返回基础 lexicon（零开销，
     * 不引入额外装饰层）。
     */
    public static Lexicon wrap(Lexicon base, Map<SemanticTokenKind, List<String>> aliasSet) {
        if (base == null) {
            throw new IllegalArgumentException("base lexicon required");
        }
        if (aliasSet == null || aliasSet.isEmpty()) {
            return base;
        }
        return new AliasOverlayLexicon(base, aliasSet);
    }

    @Override
    public Map<SemanticTokenKind, List<String>> getAliases() {
        return aliases;
    }

    // ─── 其余全部透传基础 lexicon ───────────────────────────────

    @Override
    public String getId() {
        return base.getId();
    }

    @Override
    public String getName() {
        return base.getName();
    }

    @Override
    public Direction getDirection() {
        return base.getDirection();
    }

    @Override
    public Map<SemanticTokenKind, String> getKeywords() {
        return base.getKeywords();
    }

    @Override
    public PunctuationConfig getPunctuation() {
        return base.getPunctuation();
    }

    @Override
    public CanonicalizationConfig getCanonicalization() {
        return base.getCanonicalization();
    }

    @Override
    public ErrorMessages getMessages() {
        return base.getMessages();
    }
}
