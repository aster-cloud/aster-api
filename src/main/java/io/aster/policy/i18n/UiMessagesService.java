package io.aster.policy.i18n;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.redis.datasource.pubsub.PubSubCommands.RedisSubscriber;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一语言包的界面文案（ui-messages）内存仓与热刷新（ADR 0018 Phase 2）。
 *
 * <p><b>启动加载</b>：从 classpath 资源 {@code ui-messages/<locale-id>.json} 读入内存
 * {@link ConcurrentHashMap}。这些资源由 <b>aster-api 自带</b>（{@code src/main/resources/
 * ui-messages/}），其真相源是 {@code @aster-cloud/ui-messages} npm 包 / aster-lang-locales
 * 仓的 {@code ui-messages/}（ADR 0018：界面文案走独立 npm 通道、<b>不进语言包 JVM jar</b>，
 * 故 api 不从 {@code runtimeOnly en/zh/de} 的 jar 读 messages —— 那些 jar 只提供 lexicon
 * SPI）。同步方式见 {@code src/main/resources/ui-messages/README.md}。
 * {@code /api/v1/messages} 直接吐内存 —— 零 DB、零阻塞。
 *
 * <p><b>热刷新</b>：复用现有 Redis pub/sub（同 {@code PolicyCacheManager} 的分布式
 * 失效广播模式，aster-api 已有 Redis，无需引入 Kafka）。发布方（locales 发版流水线
 * 或 admin 写入口）在 channel {@value #RELOAD_CHANNEL} 发**瘦事件**
 * {@code {"locale":"en-US","version":"<sha>"}} —— 只是失效信号，本服务收到后从
 * classpath **重新加载**该 locale 并原子替换内存条目，无需重启。
 *
 * <p><b>版本（KV key 来源）</b>：每个 locale 缓存其内容的 SHA-256。{@code /api/v1/messages}
 * 把它作为 ETag / KV 版本化 key（{@code messages:<locale>:v<sha 前 8 位>}）的来源 ——
 * 文案一变 sha 变 → 前端 KV key 自然换 → 边缘自然刷新（ADR 0018 ③）。
 *
 * <p>fetch 失败时前端 fallback 到内嵌 en（绝不白屏）—— 那是 cloud 侧的责任；
 * 本服务只保证"已加载的 locale 立即可吐，未加载的返回 empty”。
 */
@ApplicationScoped
public class UiMessagesService {

    private static final Logger LOG = Logger.getLogger(UiMessagesService.class);

    /** Redis pub/sub channel：messages 热刷新失效信号。 */
    static final String RELOAD_CHANNEL = "aster.i18n.messages.reload";

    /** classpath 资源前缀。 */
    private static final String RESOURCE_PREFIX = "ui-messages/";

    /** 启动时预加载的 locale（与语言包 jar 对齐）。运行时热刷新可加载任意 locale。 */
    private static final List<String> PRELOAD_LOCALES = List.of("en-US", "zh-CN", "de-DE", "hi-IN");

    @Inject
    Instance<RedisDataSource> redisDataSource;

    /** locale-id → 已加载的 messages 条目（JSON 原文 + sha256）。 */
    private final ConcurrentHashMap<String, MessagesEntry> store = new ConcurrentHashMap<>();

    private PubSubCommands<String> pubSubCommands;
    private RedisSubscriber redisSubscriber;
    private ExecutorService reloadExecutor;

    /** 一个 locale 的界面文案：原始 JSON 文本 + 其 SHA-256（版本 / ETag 来源）。 */
    public record MessagesEntry(String json, String sha256) {}

    void onStart(@Observes StartupEvent event) {
        for (String locale : PRELOAD_LOCALES) {
            loadFromClasspath(locale).ifPresent(entry -> {
                store.put(locale, entry);
                LOG.infof("ui-messages 已加载: %s (%d bytes, sha=%s…)",
                    locale, entry.json().length(), entry.sha256().substring(0, 8));
            });
        }
        if (store.isEmpty()) {
            // P1 尚未发版时 ui-messages 资源不在 classpath —— 非致命，端点返回 404。
            LOG.info("ui-messages 资源未在 classpath（P1 语言包可能尚未发版）；/api/v1/messages 将返回空");
        }
        initReloadChannel();
    }

    /**
     * 取某 locale 的界面文案。未加载 → {@link Optional#empty()}（端点据此返回 404）。
     */
    public Optional<MessagesEntry> get(String locale) {
        if (locale == null || locale.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(locale.trim()));
    }

    /** 当前已加载的 locale 集合快照。 */
    public java.util.Set<String> loadedLocales() {
        return java.util.Set.copyOf(store.keySet());
    }

    /**
     * 从 classpath 读 {@code ui-messages/<locale>.json}，计算 sha256。
     * 资源缺失 → empty（不抛，便于 P1 未发版时优雅降级）。
     */
    private Optional<MessagesEntry> loadFromClasspath(String locale) {
        String resource = RESOURCE_PREFIX + locale + ".json";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            if (is == null) {
                return Optional.empty();
            }
            byte[] bytes = is.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(new MessagesEntry(json, sha256Hex(bytes)));
        } catch (Exception e) {
            LOG.warnf(e, "加载 ui-messages 资源失败: %s", resource);
            return Optional.empty();
        }
    }

    // ──────────────────────────────────────── Redis 热刷新

    private void initReloadChannel() {
        if (redisDataSource == null || redisDataSource.isUnsatisfied()) {
            LOG.debug("RedisDataSource 未配置，ui-messages 热刷新禁用（启动加载仍有效）");
            return;
        }
        try {
            reloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ui-messages-reload");
                thread.setDaemon(true);
                return thread;
            });
            pubSubCommands = redisDataSource.get().pubsub(String.class);
            redisSubscriber = pubSubCommands.subscribe(RELOAD_CHANNEL, payload ->
                reloadExecutor.execute(() -> handleReload(payload))
            );
            LOG.infof("ui-messages 热刷新通道已启用: %s", RELOAD_CHANNEL);
        } catch (Exception e) {
            LOG.warn("初始化 ui-messages 热刷新订阅失败（启动加载仍有效）", e);
        }
    }

    /**
     * 处理热刷新瘦事件 {@code {"locale":"en-US"}}：从 classpath 重新加载该 locale 并
     * 原子替换内存条目。包内可见，便于单测直接触发。
     */
    void handleReload(String payload) {
        if (payload == null || payload.isBlank() || !payload.stripLeading().startsWith("{")) {
            // 非 JSON 对象（含畸形 payload）直接忽略，避免 Vert.x 急切解析抛异常 + 噪声日志。
            return;
        }
        try {
            JsonObject msg = new JsonObject(payload);
            String locale = msg.getString("locale");
            if (locale == null || locale.isBlank()) {
                LOG.warnf("ui-messages 热刷新事件缺少 locale: %s", payload);
                return;
            }
            loadFromClasspath(locale.trim()).ifPresentOrElse(
                entry -> {
                    store.put(locale.trim(), entry);
                    LOG.infof("ui-messages 热刷新: %s → sha=%s…", locale, entry.sha256().substring(0, 8));
                },
                () -> LOG.warnf("ui-messages 热刷新失败：classpath 无资源 %s", locale)
            );
        } catch (Exception e) {
            LOG.warnf(e, "解析 ui-messages 热刷新事件失败: %s", payload);
        }
    }

    /**
     * 发布热刷新事件（供 admin 写入口 / 测试用）。瘦事件：只带 locale。
     */
    public void publishReload(String locale) {
        if (pubSubCommands == null || locale == null || locale.isBlank()) {
            return;
        }
        try {
            pubSubCommands.publish(RELOAD_CHANNEL,
                new JsonObject().put("locale", locale.trim()).encode());
        } catch (Exception e) {
            LOG.debug("广播 ui-messages 热刷新事件失败", e);
        }
    }

    @PreDestroy
    void shutdown() {
        if (redisSubscriber != null) {
            try {
                redisSubscriber.unsubscribe();
            } catch (Exception e) {
                LOG.debugf("ui-messages Redis unsubscribe 失败: %s", e.getMessage());
            }
        }
        if (reloadExecutor != null) {
            reloadExecutor.shutdown();
            try {
                if (!reloadExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    reloadExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                reloadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
