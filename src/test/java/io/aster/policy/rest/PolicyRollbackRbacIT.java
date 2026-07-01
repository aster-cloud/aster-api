package io.aster.policy.rest;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.rest.model.RollbackRequest;
import io.aster.policy.service.PolicyVersionService;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 红队 P1-E：rollback 是生产突变操作，权限从类级默认 MEMBER 提升到方法级 ADMIN。
 *
 * <p>本 IT **打开 RBAC**（rbac.enabled=true，区别于其它测试关 RBAC 的默认 profile），
 * 走真实 RoleEnforcementFilter：
 * <ul>
 *   <li>MEMBER → 403（权限不足，即使类级默认允许 MEMBER，方法级 ADMIN 优先）</li>
 *   <li>VIEWER → 403</li>
 *   <li>缺 X-User-Role → 403</li>
 *   <li>ADMIN / OWNER → 越过 RBAC（非 403）</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(PolicyRollbackRbacIT.RbacOnProfile.class)
public class PolicyRollbackRbacIT {

    public static class RbacOnProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // 打开 RBAC，关掉签名/apikey，隔离测试角色闸门这一层。
            return Map.of(
                "aster.security.rbac.enabled", "true",
                "aster.security.signature.enabled", "false",
                "aster.security.apikey.enabled", "false"
            );
        }
    }

    @Inject
    PolicyVersionService policyVersionService;

    private final String policyId = "aster.test.rbacRollback";
    private PolicyVersion v1, v2;

    @BeforeEach
    @Transactional
    void setUp() {
        PolicyVersion.delete("policyId", policyId);
        v1 = policyVersionService.createVersion(policyId, "aster.test", "rbackRollback",
            "function f() { return 'v1'; }", "test-user", "v1");
        v2 = policyVersionService.createVersion(policyId, "aster.test", "rbackRollback",
            "function f() { return 'v2'; }", "test-user", "v2");
        markApproved(v1);
        markApproved(v2);
    }

    @Transactional
    void markApproved(PolicyVersion version) {
        PolicyVersion managed = PolicyVersion.findById(version.id);
        managed.status = io.aster.policy.entity.VersionStatus.APPROVED;
        managed.tenantId = "test-tenant";
        managed.persist();
    }

    private io.restassured.specification.RequestSpecification rollbackReq(String role) {
        var spec = given()
            .contentType(APPLICATION_JSON)
            .body(new RollbackRequest(v1.version, "rbac probe"))
            .header("X-Tenant-Id", "test-tenant")
            .header("X-User-Id", "test-user");
        if (role != null) {
            spec = spec.header("X-User-Role", role);
        }
        return spec;
    }

    @Test
    void memberRoleRejected403() {
        rollbackReq("MEMBER")
            .when().post("/api/v1/policies/" + policyId + "/rollback")
            .then().statusCode(403);
    }

    @Test
    void viewerRoleRejected403() {
        rollbackReq("VIEWER")
            .when().post("/api/v1/policies/" + policyId + "/rollback")
            .then().statusCode(403);
    }

    @Test
    void missingRoleRejected403() {
        rollbackReq(null)
            .when().post("/api/v1/policies/" + policyId + "/rollback")
            .then().statusCode(403);
    }

    @Test
    void adminRolePassesRbac() {
        // ADMIN 越过 RBAC 闸门 → 非 403（下游正常回滚，期望 200 success）。
        int status = rollbackReq("ADMIN")
            .when().post("/api/v1/policies/" + policyId + "/rollback")
            .then().extract().statusCode();
        assertNotEquals(403, status, "ADMIN 必须越过 rollback 的 RBAC 闸门");
    }

    @Test
    void ownerRolePassesRbacAndRollsBack() {
        // OWNER > ADMIN，也应通过；并断言实际回滚成功（端到端）。
        rollbackReq("OWNER")
            .when().post("/api/v1/policies/" + policyId + "/rollback")
            .then().statusCode(200).body("success", is(true));
    }
}
