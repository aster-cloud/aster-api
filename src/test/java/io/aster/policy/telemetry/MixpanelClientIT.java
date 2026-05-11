package io.aster.policy.telemetry;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MixpanelClient 集成测试
 *
 * 用纯 Java ServerSocket 启 mock /track 端点（避免 Vert.x classloader 冲突），
 * 验证：批量 flush、定时 flush、连续失败熔断。
 */
@QuarkusTest
@TestProfile(MixpanelClientIT.MockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MixpanelClientIT {

    // 端口需在 ConfigMapping 解析前就确定；用 static 提前 pick
    private static final int MOCK_PORT = pickFreePort();
    private static final List<String> RECEIVED_BODIES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger FAIL_COUNT = new AtomicInteger(0);
    private static final AtomicBoolean ALWAYS_FAIL = new AtomicBoolean(false);
    private static ServerSocket serverSocket;
    private static ExecutorService executor;

    @Inject
    MixpanelClient client;

    public static class MockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> cfg = new HashMap<>();
            cfg.put("aster.mixpanel.enabled", "true");
            cfg.put("aster.mixpanel.token", "test-token");
            cfg.put("aster.mixpanel.base-url", "http://127.0.0.1:" + MOCK_PORT);
            cfg.put("aster.mixpanel.batch-size", "3");
            cfg.put("aster.mixpanel.flush-interval", "PT1S");
            cfg.put("aster.mixpanel.max-retries", "1");
            cfg.put("aster.mixpanel.circuit-open-threshold", "2");
            cfg.put("aster.mixpanel.circuit-cooldown", "PT5S");
            return cfg;
        }
    }

    private static int pickFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    static void startMock() throws IOException {
        executor = Executors.newCachedThreadPool();
        serverSocket = new ServerSocket(MOCK_PORT);
        executor.submit(MixpanelClientIT::acceptLoop);
    }

    @AfterAll
    static void stopMock() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @AfterEach
    void resetMockState() throws InterruptedException {
        // 给前一个测试的异步 flush 收尾
        Thread.sleep(300);
        RECEIVED_BODIES.clear();
        FAIL_COUNT.set(0);
        ALWAYS_FAIL.set(false);
    }

    private static void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                executor.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    e.printStackTrace();
                }
                return;
            }
        }
    }

    private static void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {

            // 解析最简 HTTP 请求：第一行 + headers + body
            String line = in.readLine();
            if (line == null) return;
            int contentLength = 0;
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(header.substring(15).trim());
                }
            }
            char[] body = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String bodyStr = new String(body, 0, read);

            if (line.startsWith("POST /track")) {
                if (ALWAYS_FAIL.get()) {
                    FAIL_COUNT.incrementAndGet();
                    writeResponse(out, 503, "fail");
                } else {
                    RECEIVED_BODIES.add(bodyStr);
                    writeResponse(out, 200, "1");
                }
            } else {
                writeResponse(out, 404, "");
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeResponse(OutputStream out, int status, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " OK\r\n"
            + "Content-Length: " + bodyBytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    @Test
    @Order(1)
    void enqueue_reachesBatchSize_triggersImmediateFlush() {
        for (int i = 0; i < 3; i++) {
            client.enqueue("user-" + i, "draft_published", Map.of("rule_id", "p" + i));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> !RECEIVED_BODIES.isEmpty());
        assertTrue(RECEIVED_BODIES.size() >= 1, "批量满即 flush");
        String body = RECEIVED_BODIES.get(0);
        assertTrue(body.startsWith("data="), "Mixpanel /track 期望 form data 编码");
    }

    @Test
    @Order(2)
    void enqueue_belowBatch_periodicFlushPicksUpAfterInterval() {
        client.enqueue("user-1", "draft_published", Map.of("rule_id", "p1"));
        client.enqueue("user-2", "draft_published", Map.of("rule_id", "p2"));
        await().atMost(Duration.ofSeconds(5)).until(() -> !RECEIVED_BODIES.isEmpty());
    }

    @Test
    @Order(3)
    void persistentFailures_openCircuit_stopAcceptingEvents() throws InterruptedException {
        ALWAYS_FAIL.set(true);

        for (int i = 0; i < 9; i++) {
            client.enqueue("user-" + i, "draft_published", Map.of("rule_id", "p" + i));
            TimeUnit.MILLISECONDS.sleep(50);
        }
        // 给 1 秒让批量 flush + 重试都跑完
        TimeUnit.SECONDS.sleep(2);
        assertTrue(FAIL_COUNT.get() >= 2, "至少发生 2 次失败投递（达到熔断阈值）");

        int beforeDrop = FAIL_COUNT.get();
        for (int i = 0; i < 5; i++) {
            client.enqueue("after-circuit-" + i, "draft_published", Map.of());
        }
        TimeUnit.SECONDS.sleep(2);
        assertEquals(beforeDrop, FAIL_COUNT.get(), "熔断后不应再发出新请求");
    }
}
