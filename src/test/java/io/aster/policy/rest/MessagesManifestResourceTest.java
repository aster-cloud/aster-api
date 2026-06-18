package io.aster.policy.rest;

import io.aster.policy.i18n.UiMessagesService;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessagesManifestResource 端点逻辑测试（ADR 0020 优化 1）。
 *
 * <p>不启动 Quarkus（本仓 @QuarkusTest 需真 Postgres）—— 直接 new resource + 反射注入
 * UiMessagesService（从 test 资源加载 en-US），验 manifest 形状 / ETag / 304 / 仅列已加载
 * locale。LexiconRegistry 由 SPI 在 test classpath 填充（含 en-US/zh-CN/de-DE/hi-IN），
 * 但 ui-messages 仅 test 资源提供 en-US → manifest 只应含 en-US。
 */
@DisplayName("MessagesManifestResource 端点逻辑")
class MessagesManifestResourceTest {

    private MessagesManifestResource resource;

    /** 极简 Request stub：可配置 evaluatePreconditions(EntityTag) 的返回。 */
    private static final class StubRequest implements Request {
        Response.ResponseBuilder preconditionResult;

        @Override public String getMethod() { return "GET"; }
        @Override public Variant selectVariant(List<Variant> variants) { return null; }
        @Override public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
            return preconditionResult;
        }
        @Override public Response.ResponseBuilder evaluatePreconditions(Date lastModified) { return null; }
        @Override public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) { return null; }
        @Override public Response.ResponseBuilder evaluatePreconditions() { return null; }
    }

    @BeforeEach
    void setUp() throws Exception {
        resource = new MessagesManifestResource();
        UiMessagesService svc = new UiMessagesService();
        var reload = UiMessagesService.class.getDeclaredMethod("handleReload", String.class);
        reload.setAccessible(true);
        reload.invoke(svc, "{\"locale\":\"en-US\"}"); // 从 test 资源加载 en-US
        Field f = MessagesManifestResource.class.getDeclaredField("uiMessages");
        f.setAccessible(true);
        f.set(resource, svc);
    }

    @Test
    @DisplayName("manifest 返回 200 + 仅含已加载 locale（en-US）+ 16 位 sha + ETag")
    void manifestReturns200WithLoadedLocale() {
        Response resp = resource.manifest(new StubRequest());
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getEntityTag()).isNotNull();

        @SuppressWarnings("unchecked")
        List<MessagesManifestResource.LocaleVersion> entries =
            (List<MessagesManifestResource.LocaleVersion>) resp.getEntity();
        // 只 en-US 有 ui-messages（zh/de/hi test 资源未提供）。
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).locale()).isEqualTo("en-US");
        assertThat(entries.get(0).sha()).hasSize(16); // sha256 前 16 位
    }

    @Test
    @DisplayName("If-None-Match 命中 → 304（透传 Request.evaluatePreconditions）")
    void ifNoneMatchReturns304() {
        StubRequest req = new StubRequest();
        req.preconditionResult = Response.notModified();
        Response resp = resource.manifest(req);
        assertThat(resp.getStatus()).isEqualTo(304);
        assertThat(resp.getEntityTag()).isNotNull();
    }

    @Test
    @DisplayName("manifest 带短 max-age（版本源需较快反映 sha 变化）")
    void manifestHasShortMaxAge() {
        Response resp = resource.manifest(new StubRequest());
        assertThat(resp.getHeaderString("Cache-Control")).contains("max-age=60");
        assertThat(resp.getHeaderString("Cache-Control")).contains("must-revalidate");
    }
}
