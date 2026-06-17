package io.aster.policy.rest;

import aster.core.lexicon.LexiconRegistry;
import io.aster.policy.i18n.UiMessagesService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * 界面文案（ui-messages）分发 API —— 统一语言包 Phase 2（ADR 0018）。
 *
 * <p>这是"后端权威源 + Workers KV 边缘缓存"两层结构的 L2 权威源：前端先查 KV，
 * miss 回源到此端点，回填 KV。本端点直接吐 {@link UiMessagesService} 的内存仓
 * —— 零 DB、零阻塞。
 *
 * <p><b>授权</b>：与 {@link LexiconResource}（{@code /api/v1/lexicons}）**同款边界** ——
 * 公开只读（界面文案非敏感，登录页本身也需要文案），但**受 locale 可用性开关约束**：
 * 只有在 {@link LexiconRegistry#availableIds()}（已注册且未软下线 = 后端可用性层）
 * 中的 locale 才返回，否则 404。关掉某语言后其 messages 一并 404 —— 守住"管理员
 * 设语言可用性"这个产品语义。
 *
 * <p><b>缓存</b>：响应带 {@code ETag = ui-messages 内容 SHA-256}。前端用它构造
 * Workers KV 的版本化 key（{@code messages:<locale>:v<sha 前 8 位>}）；文案一变 sha
 * 变 → KV key 自然换 → 边缘自然刷新（ADR 0018 ③）。{@code If-None-Match} 命中
 * 返回 304，省带宽。
 */
@Path("/api/v1/messages")
@Produces(MediaType.APPLICATION_JSON)
public class MessagesResource {

    private static final Logger LOG = Logger.getLogger(MessagesResource.class);

    @Inject
    UiMessagesService uiMessages;

    /**
     * 取某 locale 的全部界面文案（next-intl messages 树，38 namespace）。
     *
     * @param locale 全码 locale id（{@code en-US} / {@code zh-CN} / …），须在
     *               {@code /api/v1/lexicons} 的可用集合中
     * @return 200 + messages JSON（带 ETag）；404 若 locale 未启用或文案未加载；
     *         304 若 If-None-Match 命中
     */
    @GET
    @Path("/{locale}")
    public Response get(@PathParam("locale") String locale, @Context Request request) {
        // 授权：locale 必须在后端可用集合（与 /api/v1/lexicons 同源）。
        // 未启用 → 404（不区分"不存在"与"被管理员关闭"，避免泄露可用性配置）。
        if (!isLocaleAvailable(locale)) {
            return notFound(locale, "locale_not_available");
        }

        Optional<UiMessagesService.MessagesEntry> entry = uiMessages.get(locale);
        if (entry.isEmpty()) {
            // locale 已启用但 ui-messages 资源未加载（P1 语言包未发版时的过渡态）。
            return notFound(locale, "messages_not_loaded");
        }

        EntityTag etag = new EntityTag(entry.get().sha256());
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        if (notModified != null) {
            // If-None-Match 命中 → 304，省带宽。
            return notModified.tag(etag).build();
        }

        return Response.ok(entry.get().json())
            .tag(etag)
            // 边缘可缓存，但必须 revalidate（ETag 驱动版本化）。
            .header("Cache-Control", "public, max-age=300, must-revalidate")
            .build();
    }

    /**
     * locale 是否在后端可用集合（已注册 + 未软下线）。
     * 复用 LexiconRegistry，与 {@code /api/v1/lexicons} 单一来源。
     */
    private boolean isLocaleAvailable(String locale) {
        if (locale == null || locale.isBlank()) {
            return false;
        }
        return LexiconRegistry.getInstance().availableIds().contains(locale.trim());
    }

    private Response notFound(String locale, String reason) {
        LOG.debugf("messages 404 locale=%s reason=%s", locale, reason);
        return Response.status(Response.Status.NOT_FOUND)
            .entity(java.util.Map.of("error", reason, "locale", String.valueOf(locale)))
            .header("Cache-Control", "no-store")
            .build();
    }
}
