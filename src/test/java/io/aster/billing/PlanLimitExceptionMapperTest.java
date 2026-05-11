package io.aster.billing;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanLimitException → 402 + JSON 映射测试
 *
 * 严格验证响应契约，避免前端 UpgradeBlocker 解析失败
 */
class PlanLimitExceptionMapperTest {

    private final PlanLimitExceptionMapper mapper = new PlanLimitExceptionMapper();

    @Test
    @SuppressWarnings("unchecked")
    void reviewerRequired_recommendsPro() {
        Response resp = mapper.toResponse(new PlanLimitException("reviewer_required"));
        assertEquals(402, resp.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, resp.getMediaType().toString());

        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals(true, body.get("upgrade"));
        assertEquals("reviewer_required", body.get("reason"));
        assertEquals("pro", body.get("recommendedPlan"));
        assertTrue(((String) body.get("message")).contains("reviewer_required"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sso_recommendsEnterprise() {
        Response resp = mapper.toResponse(new PlanLimitException("sso"));
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("enterprise", body.get("recommendedPlan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dataResidency_recommendsEnterprise() {
        Response resp = mapper.toResponse(new PlanLimitException("data_residency"));
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("enterprise", body.get("recommendedPlan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void auditRetention_recommendsEnterprise() {
        Response resp = mapper.toResponse(new PlanLimitException("audit_retention"));
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("enterprise", body.get("recommendedPlan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void teamMemberInvite_recommendsPro() {
        Response resp = mapper.toResponse(new PlanLimitException("team_member_invite"));
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("pro", body.get("recommendedPlan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedRules_recommendsPro() {
        Response resp = mapper.toResponse(new PlanLimitException("published_rules"));
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("pro", body.get("recommendedPlan"));
    }
}
