package io.aster.policy.rest;

import aster.core.lexicon.LexiconRegistry;
import io.aster.policy.i18n.MessagesValidator;
import io.aster.policy.i18n.UiMessagesService;
import io.aster.policy.security.AdminHmacVerifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时文案编辑（admin 写入口）—— ADR 0021 方案 A。
 *
 * <p>{@code PUT /api/v1/admin/messages/{locale}} 把 admin 编辑的 messages 树写入 Redis
 * 运行时覆盖层 → 本 pod 自更新内存 → Redis pub/sub 广播给其它 pod → manifest sha 变 →
 * 前端版本化 KV 自动刷新（ADR 0020 复利）→ **无需重新部署即时生效**。
 * {@code DELETE} 回退 classpath 基线。
 *
 * <p><b>鉴权</b>：资源内部 HMAC（{@link AdminHmacVerifier}，同 {@code LexiconAdminResource}
 * 的 admin 验签）。admin/messages 路径在 TenantFilter + RequestSignatureFilter **被豁免**
 * （跳过全局 tenant/签名校验，避免与资源内 HMAC 双重验签冲突——跳过 ≠ 不鉴权，鉴权由本
 * 资源的 HMAC 保证）。**绝不**走 messages 公开读豁免（{@code MessagesPathMatcher} 只豁免
 * {@code /api/v1/messages/<locale>} 和 manifest 读路径，与本 admin 写路径无交集）。
 *
 * <p><b>输入校验</b>：Content-Type 强制 JSON + 体积上限 + {@link MessagesValidator}（JSON
 * 合法 + 键集 parity + 占位符 parity vs classpath 基线）+ locale 须在可用集合。
 */
@Path("/api/v1/admin/messages")
@Produces(MediaType.APPLICATION_JSON)
public class MessagesAdminResource {

    private static final Logger LOG = Logger.getLogger(MessagesAdminResource.class);

    /** messages 树体积上限（基线 ~129KB → 256KB 上限，防 DoS）。 */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    @Inject
    UiMessagesService uiMessages;

    @Inject
    AdminHmacVerifier hmac;

    @PUT
    @Path("/{locale}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("locale") String locale,
                        @Context HttpHeaders headers,
                        String body) {
        String loc = locale == null ? "" : locale.trim();

        // 1) 体积上限（DoS）。
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            throw badRequest("empty_body", "messages body 不能为空");
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw badRequest("body_too_large", "messages 超过 " + MAX_BODY_BYTES + " 字节上限");
        }

        // 2) HMAC 验签（资源内部，与 lexicon admin 同方案）。
        hmac.verify(headers, "PUT", "/api/v1/admin/messages/" + loc,
            MediaType.APPLICATION_JSON, bytes.length, AdminHmacVerifier.sha256Hex(bytes));

        // 3) locale 须在可用集合（与读端点同源；未注册 locale 不可写）。
        if (!isLocaleAvailable(loc)) {
            throw badRequest("locale_not_available", "locale 未注册或已下线: " + loc);
        }

        // 4) 校验：JSON 合法 + 键集/占位符 parity vs classpath 基线。
        Optional<String> baseline = uiMessages.baselineJson(loc);
        if (baseline.isEmpty()) {
            // 无基线（locale 未发版文案）→ 无 parity 可比，拒绝写入（避免凭空建立无基线覆盖）。
            throw badRequest("no_baseline", "该 locale 无 classpath 基线文案，无法校验 parity");
        }
        MessagesValidator.Result vr = MessagesValidator.validate(body, baseline.get());
        if (!vr.ok()) {
            throw badRequest("validation_failed", vr.error());
        }

        // 5) 写入 Redis 覆盖层 + 本 pod 自更新 + 广播。
        Optional<UiMessagesService.WriteResult> written = uiMessages.writeOverride(loc, body);
        if (written.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "store_unavailable", "message", "Redis 运行时覆盖层不可用"))
                .build();
        }
        UiMessagesService.WriteResult result = written.get();

        // 6) 审计（结构化日志，不打完整文案；记 actor/locale/新 sha/propagation）。
        String actor = headers.getHeaderString("X-User-Id");
        LOG.infof("ui-messages 运行时编辑: locale=%s actor=%s newSha=%s bytes=%d propagated=%s",
            loc, actor == null ? "<hmac>" : actor, result.entry().sha256().substring(0, 16),
            bytes.length, result.propagated());

        // 广播失败 → 202 degraded（本 pod 已生效，其它 pod 待下次 reload/重启从 Redis 纠偏）。
        Response.Status status = result.propagated() ? Response.Status.OK : Response.Status.ACCEPTED;
        return Response.status(status).entity(Map.of(
            "locale", loc,
            "sha", result.entry().sha256().substring(0, 16),
            "bytes", bytes.length,
            "propagated", result.propagated()
        )).build();
    }

    @DELETE
    @Path("/{locale}")
    public Response delete(@PathParam("locale") String locale, @Context HttpHeaders headers) {
        String loc = locale == null ? "" : locale.trim();
        hmac.verify(headers, "DELETE", "/api/v1/admin/messages/" + loc, null, 0, null);
        if (!isLocaleAvailable(loc)) {
            throw badRequest("locale_not_available", "locale 未注册或已下线: " + loc);
        }
        UiMessagesService.DeleteResult result = uiMessages.deleteOverride(loc);
        String actor = headers.getHeaderString("X-User-Id");
        LOG.infof("ui-messages 运行时覆盖删除: locale=%s actor=%s removed=%s propagated=%s",
            loc, actor == null ? "<hmac>" : actor, result.removed(), result.propagated());
        // 删了但广播失败 → 202 degraded（其它 pod 待下次 reload 从 Redis 真相源纠偏）。
        Response.Status status = (result.removed() && !result.propagated())
            ? Response.Status.ACCEPTED : Response.Status.OK;
        return Response.status(status)
            .entity(Map.of("locale", loc, "reverted", result.removed(), "propagated", result.propagated()))
            .build();
    }

    private boolean isLocaleAvailable(String locale) {
        if (locale == null || locale.isBlank()) return false;
        return LexiconRegistry.getInstance().availableIds().contains(locale.trim());
    }

    private static WebApplicationException badRequest(String error, String message) {
        return new WebApplicationException(
            Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", error, "message", message))
                .build());
    }
}
