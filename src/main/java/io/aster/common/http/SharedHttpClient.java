package io.aster.common.http;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared {@link HttpClient} instance for internal HMAC-signed cloud calls.
 *
 * <p>R32 hotfix v2 起 {@link io.aster.security.apikey.ApiKeyVerifierService}
 * 和 {@link io.aster.billing.ApiQuotaGuard} 都改用 {@code java.net.http.HttpClient}
 * 取代 Vert.x WebClient（避免 event-loop 回调排队导致 callback never fires）。
 * 若每次调用都 {@code HttpClient.newBuilder().build()}，每个请求都要重新做
 * HTTPS 握手 + connection setup，把 cf 边缘的 ~50ms 放大到 ~500ms+。
 *
 * <p>本 bean 把 HttpClient 收敛成单例：HTTP/1.1 keep-alive 连接池在多次调用之间复用，
 * TLS session resumption 自动生效，常规 hot-path 请求降到 single-digit ms。
 *
 * <p>线程模型：HttpClient 内部用自带的 selector 线程做 NIO；
 * {@link HttpClient#send(java.net.http.HttpRequest, java.net.http.HttpResponse.BodyHandler) send()}
 * 是同步阻塞调用，会在 caller 线程上等待响应。配合外层 verifyPool / 同步阻塞，
 * 不依赖任何 Vert.x event-loop 调度。
 */
@ApplicationScoped
public class SharedHttpClient {

    private volatile HttpClient client;
    private volatile ExecutorService executor;
    private final AtomicLong threadCounter = new AtomicLong();

    /**
     * Lazy-init 单例 HttpClient。
     *
     * <p>HTTP_1_1 因为 cf 边缘对 HTTP/2 内部 endpoint 偶发握手抖动；
     * 1.1 + keep-alive 在 prod 实测最稳。connectTimeout 5s 留给 cold
     * connection；后续请求复用既有连接，几乎 0 cost。
     */
    public HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    executor = Executors.newFixedThreadPool(4, r -> {
                        Thread t = new Thread(r,
                            "shared-http-" + threadCounter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                    local = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .version(HttpClient.Version.HTTP_1_1)
                        .executor(executor)
                        .build();
                    client = local;
                }
            }
        }
        return local;
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
