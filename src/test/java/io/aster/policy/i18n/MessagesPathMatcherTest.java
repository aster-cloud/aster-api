package io.aster.policy.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MessagesPathMatcher} 单段精确匹配测试（ADR 0018 P2 + Codex 安全审查收紧）。
 *
 * <p>perimeter 豁免只能放行 {@code /api/v1/messages/<locale>} 单段读端点，
 * 必须拒绝多段子路径、路径穿越、裸路径、兄弟路径——否则 perimeter 豁免会变成
 * 整子树放行，未来子资源或 {@code ..} 输入可能绕过 Tenant/HMAC 校验。
 */
@DisplayName("MessagesPathMatcher 单段精确匹配")
class MessagesPathMatcherTest {

    @Test
    @DisplayName("合法单段 locale → 豁免（带/不带前导斜杠、各 locale 形态）")
    void singleLocaleExempt() {
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/en-US")).isTrue();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/zh-CN")).isTrue();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/hi-IN")).isTrue();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("api/v1/messages/de-DE")).isTrue();
        // 未知/畸形 locale 仍豁免 perimeter——交给 MessagesResource 可用性开关 404。
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/xx-XX")).isTrue();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/garbage")).isTrue();
    }

    @Test
    @DisplayName("裸路径（无 locale 段）→ 不豁免")
    void bareMessagesPathNotExempt() {
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages")).isFalse();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/")).isFalse();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("api/v1/messages")).isFalse();
    }

    @Test
    @DisplayName("多段子路径 → 不豁免（不豁免整个子树）")
    void multiSegmentNotExempt() {
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/en-US/extra")).isFalse();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/en-US/")).isFalse();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/a/b/c")).isFalse();
    }

    @Test
    @DisplayName("路径穿越段 → 不豁免（防 /messages/../policies/evaluate 绕过 perimeter）")
    void pathTraversalNotExempt() {
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/..")).isFalse();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/.")).isFalse();
        // 含穿越的多段（前缀虽匹配，但有第二段斜杠）也被多段规则拒掉。
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages/../policies/evaluate")).isFalse();
    }

    @Test
    @DisplayName("兄弟路径 → 不豁免（slash boundary）")
    void siblingPathNotExempt() {
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messages-admin/foo")).isFalse();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v1/messagesx/en-US")).isFalse();
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath("/api/v2/messages/en-US")).isFalse();
    }

    @Test
    @DisplayName("null → 不豁免")
    void nullNotExempt() {
        assertThat(MessagesPathMatcher.isSingleLocaleMessagesPath(null)).isFalse();
    }
}
