package io.aster.policy.rest;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 词法表（语言包）查询 API
 *
 * <p>面向前端的"当前可用语言列表"快照端点。aster-cloud 在首次加载时调用此端点
 * 拿到初始集合，随后通过 SSE（{@link LexiconStreamResource}）订阅运行期变更。
 *
 * <p>本端点公开访问 —— 仅暴露语言元数据，不含敏感信息；前端在用户登录前也需要
 * 知道哪些语言可选才能正确渲染登录页本身的语言选择器。
 */
@Path("/api/v1/lexicons")
@Produces(MediaType.APPLICATION_JSON)
public class LexiconResource {

    private static final Logger LOG = Logger.getLogger(LexiconResource.class);

    /**
     * 返回当前已注册且**未被软下线**的所有 lexicon。
     *
     * <p>响应形状（数组）：
     * <pre>
     * [
     *   { "id": "en-US", "name": "English (US)", "direction": "ltr" },
     *   { "id": "zh-CN", "name": "简体中文",     "direction": "ltr" }
     * ]
     * </pre>
     *
     * <p>顺序：按 id 字典序，便于前端缓存 hash 计算。
     */
    @GET
    public Response list() {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        List<LexiconInfo> result = new ArrayList<>();
        for (String id : registry.availableIds()) {
            registry.get(id).ifPresent(lex -> result.add(LexiconInfo.from(lex)));
        }
        result.sort((a, b) -> a.id().compareTo(b.id()));
        LOG.debugf("Returning %d available lexicons", result.size());
        // Suggestion: no-store —— 热插拔后立即生效，禁止任何缓存层服务旧快照
        return Response.ok(result)
            .header("Cache-Control", "no-store")
            .build();
    }

    /**
     * DTO：单个 lexicon 的对外形状。
     *
     * <p>故意只暴露三个字段：
     * <ul>
     *   <li>id —— locale 标识，前端用于 URL / cookie 设置</li>
     *   <li>name —— 显示名称（"English"、"简体中文"），下拉用</li>
     *   <li>direction —— ltr/rtl，影响 CSS dir 属性</li>
     * </ul>
     * keyword 表、punctuation 等是后端解析器内部细节，不在 BFF 契约中。
     */
    public record LexiconInfo(String id, String name, String direction) {
        public static LexiconInfo from(Lexicon lex) {
            String dir = lex.getDirection() == null ? "ltr"
                : lex.getDirection().name().toLowerCase();
            return new LexiconInfo(lex.getId(), lex.getName(), dir);
        }
    }
}
