package io.aster.policy.rest;

import aster.core.lexicon.LexiconRegistry;
import io.aster.policy.i18n.UiMessagesService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 界面文案版本清单（ui-messages manifest）—— ADR 0020 优化 1。
 *
 * <p>返回每个可用 + 已加载 locale 的 {@code {locale, sha}}，供前端构造**版本化的
 * Workers KV key**（{@code ui-messages:<locale>:v<sha>}）。这样后端 messages 版本一变，
 * 前端 KV key 自然换 → 边缘随版本即时刷新，**不再靠固定 key + 300s TTL 等过期**
 * （修 ADR 0018 P2 的 KV 固定 key stale 问题）。
 *
 * <p><b>授权</b>：与 {@link MessagesResource} / {@code /api/v1/lexicons} **同款边界** ——
 * 公开只读、受 locale 可用性开关约束（只列 {@link LexiconRegistry#availableIds()} 中且
 * 已加载 messages 的 locale）。perimeter 豁免见 {@code MessagesPathMatcher} /
 * {@code TenantFilter} / {@code RequestSignatureFilter}（{@code /api/v1/messages-manifest}
 * 路径已加入豁免，否则会被 X-Tenant-Id 校验拦——同 PR #63 教训）。
 *
 * <p><b>缓存</b>：manifest 自身带 ETag（= 所有 {locale,sha} 拼串的 sha256）+ 短
 * {@code max-age}。前端拿 manifest 自身可短缓存，sha 列表变则 manifest ETag 变。
 */
@Path("/api/v1/messages-manifest")
@Produces(MediaType.APPLICATION_JSON)
public class MessagesManifestResource {

    private static final Logger LOG = Logger.getLogger(MessagesManifestResource.class);

    @Inject
    UiMessagesService uiMessages;

    /** 单个 locale 的版本条目。 */
    public record LocaleVersion(String locale, String sha) {}

    @GET
    public Response manifest(@Context Request request) {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        List<LocaleVersion> entries = new ArrayList<>();
        for (String id : registry.availableIds()) {
            // 只列既可用又已加载 messages 的 locale（未加载的不进 manifest，
            // 前端对缺失 locale 走内嵌兜底）。
            uiMessages.shortSha(id).ifPresent(sha -> entries.add(new LocaleVersion(id, sha)));
        }
        entries.sort((a, b) -> a.locale().compareTo(b.locale()));

        EntityTag etag = new EntityTag(manifestEtag(entries));
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        if (notModified != null) {
            return notModified.tag(etag).build();
        }

        LOG.debugf("messages-manifest: %d locale(s)", entries.size());
        return Response.ok(entries)
            .tag(etag)
            // 边缘可缓存但短期 revalidate：manifest 是版本源，要相对快地反映 sha 变化。
            .header("Cache-Control", "public, max-age=60, must-revalidate")
            .build();
    }

    /** manifest 自身的 ETag = 各 {locale}:{sha} 拼串的 sha256。列表变则 ETag 变。 */
    private static String manifestEtag(List<LocaleVersion> entries) {
        StringBuilder sb = new StringBuilder();
        for (LocaleVersion e : entries) {
            sb.append(e.locale()).append(':').append(e.sha()).append(';');
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 必有；理论不可达。退化为内容长度，保证不崩。
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }
}
