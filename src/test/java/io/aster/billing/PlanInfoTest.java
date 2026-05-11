package io.aster.billing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanInfo / PlanLimitException 单元测试
 */
class PlanInfoTest {

    @Test
    void failOpen_returnsMostPermissive() {
        PlanInfo info = PlanInfo.failOpen();
        assertEquals("pro", info.plan());
        assertTrue(info.allowsApproval(), "fail-open 必须放行审批，避免业务被 plan 系统拖死");
        assertEquals(-1, info.maxTeamMembers(), "fail-open 不限制成员数");
        assertFalse(info.isFreePlan());
    }

    @Test
    void isFreePlan_onlyFreeReturnsTrue() {
        assertTrue(new PlanInfo("free", null, false, 1, 1000, 0).isFreePlan());
        assertFalse(new PlanInfo("pro", null, true, -1, 50000, 5000).isFreePlan());
        assertFalse(new PlanInfo("enterprise", null, true, -1, -1, -1).isFreePlan());
    }

    @Test
    void grandfatherCustomer_preservesLegacyTier() {
        PlanInfo info = new PlanInfo("pro", "team", true, -1, 50000, 5000);
        assertEquals("team", info.legacyTier());
        assertEquals("pro", info.plan());
        assertTrue(info.allowsApproval());
    }

    @Test
    void apiAccessAllowed_freePlanIsBlocked() {
        assertFalse(new PlanInfo("free", null, false, 1, 100, 0).apiAccessAllowed());
        assertTrue(new PlanInfo("pro", null, true, 5, 5000, 5000).apiAccessAllowed());
        assertTrue(new PlanInfo("enterprise", null, true, -1, -1, -1).apiAccessAllowed());
    }

    @Test
    void unlimitedApi_onlyEnterprise() {
        assertFalse(new PlanInfo("free", null, false, 1, 100, 0).unlimitedApi());
        assertFalse(new PlanInfo("pro", null, true, 5, 5000, 5000).unlimitedApi());
        assertTrue(new PlanInfo("enterprise", null, true, -1, -1, -1).unlimitedApi());
    }

    @Test
    void planLimitException_messageFollowsContract() {
        PlanLimitException ex = new PlanLimitException("reviewer_required");
        assertEquals("reviewer_required", ex.reason());
        assertEquals("upgrade_required:reviewer_required", ex.getMessage(),
            "message 格式必须与 ExceptionMapper 期望一致");
    }
}
