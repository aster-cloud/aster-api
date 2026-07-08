package io.aster.llm.api;

import io.aster.llm.model.ByokOverride;
import io.aster.security.apikey.InternalCallerFilter;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ByokEnvelopeParser 单测（Phase 2 BYOK）：只对 HMAC 验签请求解析 _byok，未验签一律忽略。
 */
class ByokEnvelopeParserTest {

    private final ByokEnvelopeParser parser = new ByokEnvelopeParser();

    private ContainerRequestContext ctx(boolean verified, String bodyJson) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(InternalCallerFilter.HMAC_VERIFIED_PROP))
            .thenReturn(verified ? Boolean.TRUE : null);
        when(ctx.getProperty(InternalCallerFilter.VERIFIED_BODY_PROP))
            .thenReturn(bodyJson == null ? null : bodyJson.getBytes(StandardCharsets.UTF_8));
        return ctx;
    }

    @Test
    @DisplayName("已验签 + 合法 _byok → 解析出 ByokOverride")
    void parsesValidEnvelope() {
        String body = "{\"goal\":\"x\",\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"sk-user\"}}";
        ByokOverride byok = parser.parse(ctx(true, body));
        assertThat(byok).isNotNull();
        assertThat(byok.provider()).isEqualTo("openai");
        assertThat(byok.apiKey()).isEqualTo("sk-user");
    }

    @Test
    @DisplayName("★未验签请求即使带 _byok → 一律忽略（防浏览器/公网注入）")
    void ignoresEnvelopeWhenNotHmacVerified() {
        String body = "{\"goal\":\"x\",\"_byok\":{\"provider\":\"openai\",\"apiKey\":\"sk-user\"}}";
        assertThat(parser.parse(ctx(false, body))).isNull();
    }

    @Test
    @DisplayName("已验签但 body 无 _byok → null（平台路径）")
    void nullWhenNoEnvelope() {
        assertThat(parser.parse(ctx(true, "{\"goal\":\"x\"}"))).isNull();
    }

    @Test
    @DisplayName("★_byok 存在但字段不全（缺 apiKey）→ 抛错 fail-closed（不静默回退平台）")
    void throwsWhenEnvelopePresentButInvalid() {
        String body = "{\"_byok\":{\"provider\":\"openai\"}}";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parse(ctx(true, body)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("body 非 JSON → null，不抛异常")
    void nullWhenBodyNotJson() {
        assertThat(parser.parse(ctx(true, "not-json"))).isNull();
    }

    @Test
    @DisplayName("null context / 无 verified body → null")
    void nullWhenNoContextOrBody() {
        assertThat(parser.parse(null)).isNull();
        assertThat(parser.parse(ctx(true, null))).isNull();
    }
}
