package io.aster.policy.parser;

import aster.core.lexicon.SemanticTokenKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D 用户别名校验测试（ADR 0022 §11.5，堵 Codex High-3 社会工程攻击面）。
 */
class UserAliasValidatorTest {

    @Test
    void allowsLowRiskMultiWordOperatorAlias() {
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by")), "en-US");
        assertTrue(r.valid(), () -> r.errors().toString());
    }

    @Test
    void rejectsSensitiveKindAlias_socialEngineering() {
        // High-3：用户把别名挂到 RETURN（审批者看别名、实际批准的是 RETURN 语义）
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.RETURN, List.of("approve as")), "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("不允许为")));
    }

    @Test
    void rejectsControlFlowAndImportAndEffectKinds() {
        for (SemanticTokenKind sensitive : List.of(
                SemanticTokenKind.IF, SemanticTokenKind.OTHERWISE, SemanticTokenKind.MATCH,
                SemanticTokenKind.IMPORT, SemanticTokenKind.IO, SemanticTokenKind.AND,
                SemanticTokenKind.FUNC_TO, SemanticTokenKind.WORKFLOW)) {
            var r = UserAliasValidator.validate(
                Map.of(sensitive, List.of("some phrase")), "en-US");
            assertFalse(r.valid(), "应拒绝敏感 kind: " + sensitive);
        }
    }

    @Test
    void rejectsSingleWordAlias_userspaceCollision() {
        // 单词别名占标识符命名空间
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("scaled")), "en-US");
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("多词")));
    }

    @Test
    void rejectsAliasShadowingCanonical() {
        // 别名与某规范关键词同形（"plus" 是 PLUS 的规范拼写）
        var r = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("plus times")), "en-US");
        // "plus times" 是多词、不撞单词规范，应通过；改测真正撞规范的多词需要规范本身是多词。
        // 这里验证不遮蔽逻辑对多词规范（如 "divided by"）生效：
        var r2 = UserAliasValidator.validate(
            Map.of(SemanticTokenKind.TIMES, List.of("divided by")), "en-US");
        assertFalse(r2.valid());
        assertTrue(r2.errors().stream().anyMatch(e -> e.contains("遮蔽")));
    }

    @Test
    void nullAndEmptyAreValid() {
        assertTrue(UserAliasValidator.validate(null, "en-US").valid());
        assertTrue(UserAliasValidator.validate(Map.of(), "en-US").valid());
    }
}
