package io.aster.billing;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanGateService 集成测试
 *
 * mock aster-cloud /api/internal/tenant/{id}/plan，覆盖：
 *   - 正常返回 + Caffeine 缓存命中
 *   - 5xx → fail-open 返回 Pro
 *   - 缓存失效 (invalidate) 后再次发起请求
 */
@QuarkusTest
@TestProfile(PlanGateServiceIT.MockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlanGateServiceIT {

    private static final int MOCK_PORT = pickFreePort();
    private static final AtomicInteger HIT_COUNT = new AtomicInteger(0);
    private static final AtomicReference<String> RESPONSE_PLAN = new AtomicReference<>("pro");
    private static final AtomicInteger STATUS_CODE = new AtomicInteger(200);
    private static ServerSocket serverSocket;
    private static ExecutorService executor;

    @Inject
    PlanGateService planGate;

    public static class MockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> cfg = new HashMap<>();
            cfg.put("aster.plan-gate.enabled", "true");
            cfg.put("aster.plan-gate.cloud-internal-url", "http://127.0.0.1:" + MOCK_PORT);
            // dev：不设 hmac-key 即跳过签名
            cfg.put("aster.plan-gate.cache-ttl", "PT1M");
            cfg.put("aster.plan-gate.request-timeout", "PT2S");
            cfg.put("aster.plan-gate.fail-open", "true");
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
        executor.submit(PlanGateServiceIT::acceptLoop);
    }

    @AfterAll
    static void stopMock() throws IOException {
        if (serverSocket != null) serverSocket.close();
        if (executor != null) executor.shutdownNow();
    }

    @BeforeEach
    void resetMock() {
        HIT_COUNT.set(0);
        RESPONSE_PLAN.set("pro");
        STATUS_CODE.set(200);
    }

    private static void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket s = serverSocket.accept();
                executor.submit(() -> handle(s));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) e.printStackTrace();
                return;
            }
        }
    }

    private static void handle(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {

            String reqLine = in.readLine();
            if (reqLine == null) return;
            // drain headers
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip */ }

            if (reqLine.startsWith("GET /api/internal/tenant/")) {
                HIT_COUNT.incrementAndGet();
                int status = STATUS_CODE.get();
                if (status != 200) {
                    writeResponse(out, status, "{\"error\":\"injected\"}");
                    return;
                }
                String body = "{\"plan\":\"" + RESPONSE_PLAN.get() + "\","
                    + "\"legacyTier\":null,"
                    + "\"allowsApproval\":" + ("free".equals(RESPONSE_PLAN.get()) ? "false" : "true") + ","
                    + "\"maxTeamMembers\":-1,"
                    + "\"evaluationsLimit\":50000}";
                writeResponse(out, 200, body);
            } else {
                writeResponse(out, 404, "");
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeResponse(OutputStream out, int status, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + bodyBytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    @Test
    @Order(1)
    void normalLookup_returnsPlan_andCachesIt() {
        RESPONSE_PLAN.set("pro");
        PlanInfo first = planGate.lookupPlan("tenant-A");
        assertNotNull(first);
        assertEquals("pro", first.plan());
        assertTrue(first.allowsApproval());

        // 第二次同 tenant 应命中缓存，不再打到 mock
        int hitsBefore = HIT_COUNT.get();
        PlanInfo cached = planGate.lookupPlan("tenant-A");
        assertEquals("pro", cached.plan());
        assertEquals(hitsBefore, HIT_COUNT.get(), "应命中缓存，不再请求 cloud");
    }

    @Test
    @Order(2)
    void freeTenant_returnsAllowsApprovalFalse() {
        RESPONSE_PLAN.set("free");
        PlanInfo info = planGate.lookupPlan("tenant-Free");
        assertEquals("free", info.plan());
        assertTrue(info.isFreePlan());
        assertEquals(false, info.allowsApproval(), "Free 档不允许审批流");
    }

    @Test
    @Order(3)
    void cloudReturns5xx_failOpen_yieldsPro() {
        STATUS_CODE.set(503);
        PlanInfo info = planGate.lookupPlan("tenant-Down");
        // failOpen=true → 按 Pro 处理（PlanInfo.failOpen()）
        assertEquals("pro", info.plan());
        assertTrue(info.allowsApproval(), "fail-open 必须放行审批，避免业务被 plan 系统拖死");
    }

    @Test
    @Order(4)
    void invalidate_forcesNextLookupToHitCloud() {
        RESPONSE_PLAN.set("pro");
        planGate.lookupPlan("tenant-Inv");
        int hitsAfterFirst = HIT_COUNT.get();
        // 第二次本应命中缓存
        planGate.lookupPlan("tenant-Inv");
        assertEquals(hitsAfterFirst, HIT_COUNT.get());

        // 主动失效后应再次打到 mock
        planGate.invalidate("tenant-Inv");
        planGate.lookupPlan("tenant-Inv");
        assertTrue(HIT_COUNT.get() > hitsAfterFirst, "invalidate 后下次 lookup 应触发实际 HTTP 请求");
    }
}
