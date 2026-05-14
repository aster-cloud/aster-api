package io.aster.policy.rest;

import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lexicon 注册表变更的 SSE 推送端点。
 *
 * <p>前端订阅 {@code /api/v1/lexicons/stream}，接收：
 * <ul>
 *   <li><b>第一帧</b>：当前快照（同 {@link LexiconResource#list()}）</li>
 *   <li><b>变更帧</b>：每当 LexiconRegistry 监听器触发后立即推送最新集合</li>
 *   <li><b>心跳帧</b>：每 25s 一次空 keep-alive，对抗代理 idle timeout</li>
 * </ul>
 *
 * <p>M10：每个订阅者都有自己的 emit lock —— 来自 watcher 线程的并发 fireChange()
 * 不会让两次序列化交错冲突，也不会让 listener 抛错击穿 emitter。
 */
@Path("/api/v1/lexicons/stream")
public class LexiconStreamResource {

    private static final Logger LOG = Logger.getLogger(LexiconStreamResource.class);

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "aster.lexicon.stream.heartbeat-seconds", defaultValue = "25")
    int heartbeatSeconds;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> stream() {
        Multi<String> changes = Multi.createFrom().emitter(emitter -> {
            LexiconRegistry registry = LexiconRegistry.getInstance();
            // M10：per-subscriber 串行锁；并发 fireChange 不交错
            ReentrantLock emitLock = new ReentrantLock();
            AtomicReference<Set<String>> lastSent = new AtomicReference<>(null);

            // 1) 立即发首帧快照
            emitSnapshot(emitter, emitLock, lastSent, registry);

            // 2) 订阅变更
            Runnable unsubscribe = registry.addChangeListener(ids -> {
                emitSnapshot(emitter, emitLock, lastSent, registry);
            });

            emitter.onTermination(unsubscribe);
        });

        if (heartbeatSeconds <= 0) {
            return changes;
        }
        // Suggestion: SSE comment-style heartbeat (注释行，浏览器静默忽略)。
        // 我们仍保留 JSON 行约定，所以心跳用字符串 "heartbeat"，前端 hook 已识别并忽略。
        Multi<String> heartbeats = Multi.createFrom().ticks()
            .every(Duration.ofSeconds(heartbeatSeconds))
            .onItem().transform(n -> "heartbeat");
        return Multi.createBy().merging().streams(changes, heartbeats);
    }

    /** 串行化：lock 内序列化 + emit，避免并发交错 / 重复。 */
    private void emitSnapshot(io.smallrye.mutiny.subscription.MultiEmitter<? super String> emitter,
                              ReentrantLock emitLock,
                              AtomicReference<Set<String>> lastSent,
                              LexiconRegistry registry) {
        emitLock.lock();
        try {
            Set<String> current = registry.availableIds();
            Set<String> prev = lastSent.get();
            if (prev != null && prev.equals(current)) {
                return; // 去重
            }
            lastSent.set(current);

            List<LexiconResource.LexiconInfo> snapshot = new ArrayList<>();
            for (String id : current) {
                registry.get(id).ifPresent(l -> snapshot.add(LexiconResource.LexiconInfo.from(l)));
            }
            snapshot.sort((a, b) -> a.id().compareTo(b.id()));
            String json = objectMapper.writeValueAsString(snapshot);
            emitter.emit(json);
        } catch (Exception e) {
            LOG.warnf(e, "SSE emit failed; closing subscriber");
            try { emitter.fail(e); } catch (Exception ignored) {}
        } finally {
            emitLock.unlock();
        }
    }
}
