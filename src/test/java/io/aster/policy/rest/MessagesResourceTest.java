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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessagesResource 端点逻辑测试（ADR 0018 Phase 2）。
 *
 * <p>不启动 Quarkus（@QuarkusTest 在本仓需真实 Postgres，见 PolicyStorageService
 * DB 化记录）—— 直接 new MessagesResource + 反射注入 UiMessagesService，
 * 用极简 Request stub 验证授权 / ETag / 404 三类行为。
 *
 * <p>LexiconRegistry 由 SPI 在普通 test classpath 自动填充（同
 * HindiLexiconAvailabilityTest），故 availableIds() 含 en-US/zh-CN/de-DE/hi-IN。
 * ui-messages 资源仅 src/test/resources/ui-messages/ 提供 en-US（P1 未发版）。
 */
@DisplayName("MessagesResource 端点逻辑")
class MessagesResourceTest {

    private MessagesResource resource;

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
        resource = new MessagesResource();
        // 注入一个真实 UiMessagesService，并触发 en-US 从 classpath 加载。
        // handleReload 是包内可见（io.aster.policy.i18n），本测试在 rest 包，故反射调用。
        UiMessagesService svc = new UiMessagesService();
        var reload = UiMessagesService.class.getDeclaredMethod("handleReload", String.class);
        reload.setAccessible(true);
        reload.invoke(svc, "{\"locale\":\"en-US\"}"); // 从 test 资源加载 en-US
        Field f = MessagesResource.class.getDeclaredField("uiMessages");
        f.setAccessible(true);
        f.set(resource, svc);
    }

    @Test
    @DisplayName("en-US：可用且已加载 → 200 + messages + ETag")
    void enUsReturns200WithEtag() {
        Response resp = resource.get("en-US", new StubRequest());
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getEntityTag()).isNotNull();
        assertThat(resp.getEntityTag().getValue()).hasSize(64); // sha256 hex
        assertThat(resp.getHeaderString("Cache-Control")).contains("must-revalidate");
        assertThat(resp.getEntity().toString()).contains("Save");
    }

    @Test
    @DisplayName("If-None-Match 命中 → 304（透传 Request.evaluatePreconditions）")
    void ifNoneMatchReturns304() {
        StubRequest req = new StubRequest();
        // 模拟容器：ETag 命中时 evaluatePreconditions 返回 304 builder
        req.preconditionResult = Response.notModified();
        Response resp = resource.get("en-US", req);
        assertThat(resp.getStatus()).isEqualTo(304);
        assertThat(resp.getEntityTag()).isNotNull(); // 仍带 ETag
    }

    @Test
    @DisplayName("zh-CN：可用但 ui-messages 未加载 → 404 messages_not_loaded")
    void zhCnAvailableButNotLoaded() {
        Response resp = resource.get("zh-CN", new StubRequest());
        assertThat(resp.getStatus()).isEqualTo(404);
        assertThat(((Map<?, ?>) resp.getEntity()).get("error")).isEqualTo("messages_not_loaded");
    }

    @Test
    @DisplayName("未知 locale → 404 locale_not_available（不泄露可用性配置）")
    void unknownLocaleNotAvailable() {
        Response resp = resource.get("xx-XX", new StubRequest());
        assertThat(resp.getStatus()).isEqualTo(404);
        assertThat(((Map<?, ?>) resp.getEntity()).get("error")).isEqualTo("locale_not_available");
    }

    @Test
    @DisplayName("null/空白 locale → 404 locale_not_available")
    void blankLocale() {
        assertThat(resource.get(null, new StubRequest()).getStatus()).isEqualTo(404);
        assertThat(resource.get("   ", new StubRequest()).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("404 响应带 no-store，避免缓存层缓存负向结果")
    void notFoundIsNoStore() {
        Response resp = resource.get("xx-XX", new StubRequest());
        assertThat(resp.getHeaderString("Cache-Control")).isEqualTo("no-store");
    }
}
