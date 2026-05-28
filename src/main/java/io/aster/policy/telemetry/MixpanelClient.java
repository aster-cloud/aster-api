package io.aster.policy.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mixpanel HTTP 客户端
 *
 * 设计要点：
 *   - Vert.x WebClient 异步投递，避免业务路径阻塞
 *   - ConcurrentLinkedQueue 缓冲，达到 batchSize 或定时 flushInterval 触发上报
 *   - 失败指数退避重试 maxRetries 次；连续失败超阈值触发熔断
 *   - 上报失败永远不抛异常给业务侧，仅记录 Counter + 日志
 *   - shutdown 时尽力 drain 一次队列
 */
@ApplicationScoped
public class MixpanelClient {

    private static final Logger LOG = Logger.getLogger(MixpanelClient.class);

    @Inject
    MixpanelConfig config;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    io.vertx.mutiny.core.Vertx mutinyVertx;

    @Inject
    io.aster.common.http.SharedWebClient sharedWebClient;
    // P0-R19: WebClient DCL consolidated into SharedWebClient; mutinyVertx
    // still injected for setPeriodic / setTimer below.
    private final ConcurrentLinkedQueue<MixpanelEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenedAt = new AtomicLong(0);

    private Counter enqueuedCounter;
    private Counter droppedCounter;
    private Counter sentCounter;
    private Counter failedCounter;
    private Long flushTimerId;

    void onStart(@Observes StartupEvent ev) {
        enqueuedCounter = meterRegistry.counter("mixpanel_events_enqueued_total");
        droppedCounter = meterRegistry.counter("mixpanel_events_dropped_total");
        sentCounter = meterRegistry.counter("mixpanel_events_sent_total");
        failedCounter = meterRegistry.counter("mixpanel_events_failed_total");

        if (!config.enabled()) {
            LOG.info("Mixpanel 未启用：aster.mixpanel.enabled=false");
            return;
        }
        if (config.token().isEmpty()) {
            LOG.warn("Mixpanel 已启用但缺 token，所有事件将被丢弃");
        }

        long intervalMs = config.flushInterval().toMillis();
        flushTimerId = mutinyVertx.getDelegate().setPeriodic(intervalMs, id -> tryFlush());
        LOG.infof("Mixpanel 启动：baseUrl=%s, batchSize=%d, flushInterval=%s",
            config.baseUrl(), config.batchSize(), config.flushInterval());
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (flushTimerId != null) {
            mutinyVertx.getDelegate().cancelTimer(flushTimerId);
        }
        // 尽力上报一次剩余事件，超时直接放弃
        if (!queue.isEmpty()) {
            LOG.infof("Mixpanel shutdown: 尝试 flush %d 条剩余事件", queueSize.get());
            tryFlush();
        }
    }

    /**
     * 入队一条事件
     *
     * 调用方语义：永远不会因 Mixpanel 故障而失败；最差表现是事件丢失。
     */
    public void enqueue(String distinctId, String event, Map<String, Object> properties) {
        if (!config.enabled() || config.token().isEmpty() || isCircuitOpen()) {
            droppedCounter.increment();
            return;
        }
        if (queueSize.get() >= config.queueCapacity()) {
            droppedCounter.increment();
            return;
        }

        Map<String, Object> props = properties != null ? new HashMap<>(properties) : new HashMap<>();
        queue.add(new MixpanelEvent(event, distinctId, props));
        queueSize.incrementAndGet();
        enqueuedCounter.increment();

        if (queueSize.get() >= config.batchSize()) {
            tryFlush();
        }
    }

    private void tryFlush() {
        if (queue.isEmpty()) return;

        List<MixpanelEvent> batch = drainBatch(config.batchSize());
        if (batch.isEmpty()) return;

        sendBatch(batch, 0);
    }

    private List<MixpanelEvent> drainBatch(int max) {
        List<MixpanelEvent> batch = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            MixpanelEvent ev = queue.poll();
            if (ev == null) break;
            queueSize.decrementAndGet();
            batch.add(ev);
        }
        return batch;
    }

    private void sendBatch(List<MixpanelEvent> batch, int attempt) {
        String token = config.token().orElseThrow();
        JsonArray payload = new JsonArray();
        long nowSec = Instant.now().getEpochSecond();

        for (MixpanelEvent ev : batch) {
            JsonObject props = new JsonObject();
            ev.properties().forEach(props::put);
            props.put("token", token);
            props.put("distinct_id", ev.distinctId());
            props.put("time", nowSec);

            JsonObject obj = new JsonObject()
                .put("event", ev.event())
                .put("properties", props);
            payload.add(obj);
        }

        // Mixpanel /track 接受 base64 编码的 JSON 数组（POST form data）
        String encoded = Base64.getEncoder().encodeToString(
            payload.encode().getBytes(StandardCharsets.UTF_8));
        String form = "data=" + encoded;

        URI baseUri = URI.create(config.baseUrl());
        int port = baseUri.getPort() == -1 ? ("https".equals(baseUri.getScheme()) ? 443 : 80) : baseUri.getPort();
        boolean ssl = "https".equals(baseUri.getScheme());

        getClient()
            .post(port, baseUri.getHost(), "/track")
            .ssl(ssl)
            .putHeader("Content-Type", "application/x-www-form-urlencoded")
            .sendBuffer(io.vertx.core.buffer.Buffer.buffer(form))
            .onSuccess(resp -> {
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    sentCounter.increment(batch.size());
                    consecutiveFailures.set(0);
                    return;
                }
                handleFailure(batch, attempt, "HTTP " + status + ": " + resp.bodyAsString());
            })
            .onFailure(err -> handleFailure(batch, attempt, err.getMessage()));
    }

    private void handleFailure(List<MixpanelEvent> batch, int attempt, String reason) {
        if (attempt < config.maxRetries()) {
            long backoffMs = (long) (Math.pow(2, attempt) * 200);
            LOG.warnf("Mixpanel 投递失败 (attempt=%d/%d): %s，%dms 后重试",
                attempt + 1, config.maxRetries(), reason, backoffMs);
            mutinyVertx.getDelegate().setTimer(backoffMs, id -> sendBatch(batch, attempt + 1));
            return;
        }

        failedCounter.increment(batch.size());
        droppedCounter.increment(batch.size());
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= config.circuitOpenThreshold()) {
            circuitOpenedAt.set(System.currentTimeMillis());
            LOG.warnf("Mixpanel 连续失败 %d 次，熔断开路 %s", failures, config.circuitCooldown());
        }
        LOG.errorf("Mixpanel 投递放弃 %d 条事件: %s", batch.size(), reason);
    }

    private boolean isCircuitOpen() {
        long openedAt = circuitOpenedAt.get();
        if (openedAt == 0) return false;
        if (System.currentTimeMillis() - openedAt < config.circuitCooldown().toMillis()) {
            return true;
        }
        // 冷却期已过，重置
        circuitOpenedAt.set(0);
        consecutiveFailures.set(0);
        return false;
    }

    private WebClient getClient() {
        return sharedWebClient.client();
    }
}
