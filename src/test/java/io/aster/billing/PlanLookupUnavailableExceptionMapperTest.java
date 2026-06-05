package io.aster.billing;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住 plan-lookup 不可用与档位不足的语义区分（review 关键修复）：
 * lookup 故障必须是 503（可用性，可重试），而不是 402（计费/升级）。
 * 复用 402 mapper 会让一次 cloud 抖动被前端误导成"请升级套餐"。
 */
class PlanLookupUnavailableExceptionMapperTest {

    private final PlanLookupUnavailableExceptionMapper mapper =
        new PlanLookupUnavailableExceptionMapper();

    @Test
    void mapsTo503NotPaymentRequired() {
        Response r = mapper.toResponse(
            new PlanLookupUnavailableException("plan_lookup_failed"));

        assertEquals(503, r.getStatus(), "lookup 不可用必须是 503 Service Unavailable");
        assertNotEquals(402, r.getStatus(), "绝不能复用 402 Payment Required 语义");
    }

    @Test
    void includesRetryAfterAndRetryableFlag() {
        Response r = mapper.toResponse(
            new PlanLookupUnavailableException("plan_lookup_unavailable"));

        assertEquals("2", r.getHeaderString("Retry-After"), "应提示客户端稍后重试");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("plan_lookup_unavailable", body.get("error"));
        assertEquals(true, body.get("retryable"));
        assertEquals("plan_lookup_unavailable", body.get("reason"));
        // 关键：响应体不得出现 upgrade 语义（那是 402 PlanLimitException 的领域）。
        assertTrue(!body.containsKey("upgrade"), "不应包含 upgrade 字段");
    }

    @Test
    void distinctFromPlanLimitExceptionMapper() {
        // 同一 reason 字符串，两个 mapper 必须给出不同的 HTTP 语义。
        Response unavailable = mapper.toResponse(
            new PlanLookupUnavailableException("evaluations"));
        Response limit = new PlanLimitExceptionMapper().toResponse(
            new PlanLimitException("evaluations"));

        assertEquals(503, unavailable.getStatus());
        assertEquals(402, limit.getStatus());
        assertNotEquals(unavailable.getStatus(), limit.getStatus());
    }
}
