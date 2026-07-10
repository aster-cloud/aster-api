package io.aster.policy.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.llm.security.ByokAllowlistService;
import io.aster.llm.security.LlmEndpointPolicy;
import io.aster.policy.security.AdminHmacVerifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * BYOK endpoint allowlist 管理端点。
 *
 * <p>鉴权模型与 lexicon/messages admin 一致：资源内部 HMAC（{@link AdminHmacVerifier}）。
 * allowlist 是全平台 SSRF 出站边界，只有持有内部 admin HMAC 密钥的控制面可改；租户隔离不在
 * host allowlist 做，未来 tenantScope 字段仅作为数据模型扩展位返回。
 */
@Path("/api/v1/admin/byok-allowlist")
@Produces(MediaType.APPLICATION_JSON)
public class ByokAllowlistAdminResource {

    private static final Logger LOG = Logger.getLogger(ByokAllowlistAdminResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 4096;
    private static final String PATH = "/api/v1/admin/byok-allowlist";

    @Inject
    ByokAllowlistService allowlistService;

    @Inject
    LlmEndpointPolicy endpointPolicy;

    @Inject
    AdminHmacVerifier hmac;

    @GET
    public Response list(@Context HttpHeaders headers) {
        hmac.verify(headers, "GET", PATH, null, 0, null);
        return Response.ok(Map.of("endpoints", endpointPolicy.endpointViews())).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response mutate(@Context HttpHeaders headers, String body) {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            throw badRequest("empty_body", "body 不能为空");
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw badRequest("body_too_large", "body 超过 " + MAX_BODY_BYTES + " 字节上限");
        }
        hmac.verify(headers, "POST", PATH,
            MediaType.APPLICATION_JSON, bytes.length, AdminHmacVerifier.sha256Hex(bytes));

        MutateRequest req;
        try {
            req = MAPPER.readValue(body, MutateRequest.class);
        } catch (Exception e) {
            throw badRequest("invalid_json", "body 不是合法 JSON");
        }
        String action = req.action() == null ? "" : req.action().trim().toLowerCase(Locale.ROOT);
        String host = req.host() == null ? "" : req.host().trim();
        if (host.isBlank()) {
            throw badRequest("host_required", "host 不能为空");
        }

        try {
            ByokAllowlistService.MutationResult result = switch (action) {
                case "add" -> allowlistService.add(host);
                case "remove" -> allowlistService.remove(host);
                default -> throw badRequest("invalid_action", "action 必须是 add 或 remove");
            };
            LOG.infof("BYOK endpoint allowlist 变更: action=%s host=%s changed=%s actor=%s",
                action, result.host(), result.changed(), headers.getHeaderString("X-User-Id"));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("host", result.host());
            response.put("tenantScope", result.tenantScope());
            response.put("source", "dynamic");
            response.put("changed", result.changed());
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            throw badRequest("invalid_host", e.getMessage());
        }
    }

    public record MutateRequest(String action, String host) {
    }

    private static WebApplicationException badRequest(String error, String message) {
        return new WebApplicationException(
            Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", error, "message", message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
