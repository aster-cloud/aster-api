package io.aster.policy.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MessagesValidator} 校验测试（ADR 0021）：JSON 合法 + 键集 parity + 占位符 parity。
 */
@DisplayName("MessagesValidator")
class MessagesValidatorTest {

    private static final String BASELINE = """
        {
          "common": { "save": "Save", "greeting": "Hi {name}" },
          "billing": { "amount": "You owe {amount} dollars" }
        }
        """;

    @Test
    @DisplayName("键集 + 占位符一致 → ok")
    void validCandidatePasses() {
        String candidate = """
            {
              "common": { "save": "保存", "greeting": "你好 {name}" },
              "billing": { "amount": "你欠 {amount} 元" }
            }
            """;
        assertThat(MessagesValidator.validate(candidate, BASELINE).ok()).isTrue();
    }

    @Test
    @DisplayName("非 JSON → fail invalid_json")
    void invalidJsonFails() {
        var r = MessagesValidator.validate("not-json{{{", BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("invalid_json");
    }

    @Test
    @DisplayName("根非对象（数组）→ fail not_object")
    void nonObjectRootFails() {
        var r = MessagesValidator.validate("[]", BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("not_object");
    }

    @Test
    @DisplayName("缺键 → fail key_parity_mismatch（防误删）")
    void missingKeyFails() {
        String candidate = """
            { "common": { "save": "保存", "greeting": "你好 {name}" } }
            """; // 缺 billing.amount
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("key_parity_mismatch");
    }

    @Test
    @DisplayName("多键 → fail key_parity_mismatch（防误增）")
    void extraKeyFails() {
        String candidate = """
            {
              "common": { "save": "保存", "greeting": "你好 {name}", "extra": "多余" },
              "billing": { "amount": "你欠 {amount} 元" }
            }
            """;
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("key_parity_mismatch");
    }

    @Test
    @DisplayName("占位符不一致（误删 {amount}）→ fail placeholder_mismatch")
    void placeholderMismatchFails() {
        String candidate = """
            {
              "common": { "save": "保存", "greeting": "你好 {name}" },
              "billing": { "amount": "你欠很多元" }
            }
            """; // billing.amount 丢了 {amount}
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("placeholder_mismatch");
    }

    @Test
    @DisplayName("占位符改名（{name}→{username}）→ fail placeholder_mismatch")
    void placeholderRenameFails() {
        String candidate = """
            {
              "common": { "save": "保存", "greeting": "你好 {username}" },
              "billing": { "amount": "你欠 {amount} 元" }
            }
            """;
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("placeholder_mismatch");
    }

    @Test
    @DisplayName("叶子值为数组/数字 → fail invalid_leaf_type")
    void nonStringLeafFails() {
        String candidate = """
            {
              "common": { "save": ["保存"], "greeting": "你好 {name}" },
              "billing": { "amount": "你欠 {amount} 元" }
            }
            """;
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("invalid_leaf_type");
    }

    @Test
    @DisplayName("坏 ICU 语法（括号不平衡）→ fail invalid_icu（防 SSR render DoS）")
    void malformedIcuFails() {
        // greeting 占位符名一致但 ICU 语法坏（缺右括号）。
        String candidate = """
            {
              "common": { "save": "保存", "greeting": "你好 {name" },
              "billing": { "amount": "你欠 {amount} 元" }
            }
            """;
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("invalid_icu");
    }

    @Test
    @DisplayName("危险 key（__proto__）→ fail dangerous_key（防原型污染）")
    void dangerousKeyFails() {
        String candidate = """
            {
              "common": { "save": "保存", "greeting": "你好 {name}", "__proto__": "x" },
              "billing": { "amount": "你欠 {amount} 元" }
            }
            """;
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("dangerous_key");
    }

    @Test
    @DisplayName("含点 key → fail invalid_key（防路径碰撞）")
    void dottedKeyFails() {
        String candidate = """
            {
              "common": { "save": "保存", "greeting": "你好 {name}" },
              "billing.amount": "你欠 {amount} 元"
            }
            """;
        var r = MessagesValidator.validate(candidate, BASELINE);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("invalid_key");
    }

    @Test
    @DisplayName("合法 ICU plural 语法 → ok")
    void validIcuPluralPasses() {
        String base = """
            { "cart": { "items": "{count, plural, one {# item} other {# items}}" } }
            """;
        String candidate = """
            { "cart": { "items": "{count, plural, other {# 件}}" } }
            """;
        var r = MessagesValidator.validate(candidate, base);
        assertThat(r.ok()).isTrue();
    }

    @Test
    @DisplayName("真实 bundled ui-messages 自校验 → ok（验证器不误拒生产 next-intl 文案）")
    void bundledMessagesSelfValidate() {
        // 把每个真实 locale 文件当 candidate=baseline 自校验：键集天然一致，重点验
        // ICU compile + key 安全不误拒生产文案（catch ICU4J vs next-intl 语义差异）。
        String enUs = readResource("ui-messages/en-US.json");
        if (enUs == null) {
            return; // P1 未发版时 classpath 无资源 —— 跳过（与服务优雅降级一致）。
        }
        var r = MessagesValidator.validate(enUs, enUs);
        assertThat(r.ok())
            .as("真实 en-US 文案不应被验证器误拒: %s", r.error())
            .isTrue();
    }

    private static String readResource(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
