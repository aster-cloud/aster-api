package io.aster.policy.rest;

import io.aster.llm.security.ByokAllowlistService;
import io.aster.llm.security.LlmEndpointPolicy;
import io.aster.policy.security.AdminHmacVerifier;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ByokAllowlistAdminResource")
class ByokAllowlistAdminResourceTest {

    private ByokAllowlistAdminResource resource;
    private ByokAllowlistService allowlistService;
    private LlmEndpointPolicy endpointPolicy;
    private AdminHmacVerifier hmac;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        resource = new ByokAllowlistAdminResource();
        allowlistService = mock(ByokAllowlistService.class);
        endpointPolicy = mock(LlmEndpointPolicy.class);
        hmac = mock(AdminHmacVerifier.class);
        headers = mock(HttpHeaders.class);
        when(headers.getHeaderString("X-User-Id")).thenReturn("admin@example.com");

        resource.allowlistService = allowlistService;
        resource.endpointPolicy = endpointPolicy;
        resource.hmac = hmac;
    }

    @Test
    @DisplayName("GET list 先验 HMAC，返回来源标注视图")
    void listVerifiesHmacAndReturnsViews() {
        when(endpointPolicy.endpointViews()).thenReturn(List.of(
            new LlmEndpointPolicy.EndpointView("api.openai.com", 443, "", "builtin", null, false),
            new LlmEndpointPolicy.EndpointView("right.codes", 443, "", "dynamic", null, true)
        ));

        Response response = resource.list(headers);

        verify(hmac).verify(headers, "GET", "/api/v1/admin/byok-allowlist", null, 0, null);
        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertThat((List<?>) entity.get("endpoints")).hasSize(2);
    }

    @Test
    @DisplayName("POST add 先验 HMAC，再调用动态服务")
    void addVerifiesHmacAndCallsService() {
        String body = "{\"action\":\"add\",\"host\":\"right.codes\"}";
        when(allowlistService.add("right.codes"))
            .thenReturn(new ByokAllowlistService.MutationResult("right.codes", null, true));

        Response response = resource.mutate(headers, body);

        verify(hmac).verify(eq(headers), eq("POST"), eq("/api/v1/admin/byok-allowlist"),
            eq(MediaType.APPLICATION_JSON), eq((long) body.getBytes(StandardCharsets.UTF_8).length),
            eq(AdminHmacVerifier.sha256Hex(body.getBytes(StandardCharsets.UTF_8))));
        verify(allowlistService).add("right.codes");
        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertThat(entity)
            .containsEntry("host", "right.codes")
            .containsEntry("source", "dynamic")
            .containsEntry("changed", true);
        assertThat(entity).containsKey("tenantScope");
    }

    @Test
    @DisplayName("POST remove 调用动态服务")
    void removeCallsService() {
        String body = "{\"action\":\"remove\",\"host\":\"right.codes\"}";
        when(allowlistService.remove("right.codes"))
            .thenReturn(new ByokAllowlistService.MutationResult("right.codes", null, true));

        Response response = resource.mutate(headers, body);

        verify(allowlistService).remove("right.codes");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("HMAC 拒绝时端点直接拒绝，不调用服务")
    void hmacRejectionPropagates() {
        doThrow(new WebApplicationException(Response.status(403).build()))
            .when(hmac).verify(eq(headers), eq("POST"), eq("/api/v1/admin/byok-allowlist"),
                eq(MediaType.APPLICATION_JSON), anyLong(), org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> resource.mutate(headers, "{\"action\":\"add\",\"host\":\"right.codes\"}"))
            .isInstanceOf(WebApplicationException.class);
    }

    @Test
    @DisplayName("非法 action / host 返回 400")
    void invalidBodyRejected() {
        assertThatThrownBy(() -> resource.mutate(headers, "{\"action\":\"bogus\",\"host\":\"right.codes\"}"))
            .isInstanceOf(WebApplicationException.class)
            .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
            .isEqualTo(400);

        assertThatThrownBy(() -> resource.mutate(headers, "{\"action\":\"add\",\"host\":\"\"}"))
            .isInstanceOf(WebApplicationException.class)
            .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
            .isEqualTo(400);
    }
}
