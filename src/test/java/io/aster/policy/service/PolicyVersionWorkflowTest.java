package io.aster.policy.service;

import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.entity.VersionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PolicyVersionWorkflowTest extends BasePolicyVersionServiceTest {

    @Test
    void shouldCompleteDraftToActiveWorkflow() {
        PolicyVersion version = versionService.createVersion(catalog.id, "// workflow", "en-US");

        versionService.submitForApproval(version.id, "drafter");
        versionService.approveVersion(version.id, "approver");
        versionService.activateVersion(version.id, "operator");

        PolicyVersion activated = PolicyVersion.findById(version.id);
        assertEquals(VersionStatus.APPROVED, activated.status, "激活后状态保持 APPROVED");
        assertEquals("drafter", activated.submittedBy, "应记录提交人");
        assertNotNull(activated.submittedAt, "应记录提交时间");
        assertEquals("approver", activated.approvedBy, "应记录审批人");
        assertNotNull(activated.approvedAt, "应记录审批时间");
        assertEquals("operator", activated.activatedBy, "应记录激活人");
        assertTrue(activated.active, "激活后应标记 active=true");
    }

    @Test
    void shouldPreventIllegalTransitions() {
        PolicyVersion version = versionService.createVersion(catalog.id, "// illegal flow", "en-US");

        IllegalStateException approveError = assertThrows(
            IllegalStateException.class,
            () -> versionService.approveVersion(version.id, "approver"),
            "未提交直接审批应失败"
        );
        assertTrue(approveError.getMessage().contains("仅已提交版本可审批通过"));

        versionService.submitForApproval(version.id, "drafter");
        versionService.rejectVersion(version.id, "reviewer", "缺少字段");

        IllegalStateException activationError = assertThrows(
            IllegalStateException.class,
            () -> versionService.activateVersion(version.id, "operator"),
            "未审批通过不得激活"
        );
        assertTrue(activationError.getMessage().contains("仅已审批通过的版本可激活"));
    }

    @Test
    void shouldRecordRejectionMetadata() {
        PolicyVersion version = versionService.createVersion(catalog.id, "// reject flow", "en-US");

        versionService.submitForApproval(version.id, "drafter");
        PolicyVersion rejected = versionService.rejectVersion(version.id, "reviewer", "语义校验失败");

        assertEquals(VersionStatus.REJECTED, rejected.status, "拒绝后状态应为 REJECTED");
        assertEquals("reviewer", rejected.rejectedBy, "应记录拒绝人");
        assertNotNull(rejected.rejectedAt, "应记录拒绝时间");
        assertNull(rejected.approvedBy, "拒绝后审批字段应清空");
        assertNull(rejected.approvedAt, "拒绝后审批时间应清空");
        assertNotNull(rejected.notes, "应写入拒绝原因");
        assertTrue(rejected.notes.contains("语义校验失败"), "notes 应包含拒绝原因");
    }

    @Test
    void shouldSendPgNotifyOnActivation() throws Throwable {
        PolicyVersion version = versionService.createVersion(catalog.id, "// notify flow", "en-US");
        versionService.submitForApproval(version.id, "drafter");
        versionService.approveVersion(version.id, "approver");

        AtomicBoolean notifyCalled = new AtomicBoolean(false);
        EntityManager original = versionService.entityManager;
        EntityManager proxy = (EntityManager) Proxy.newProxyInstance(
            EntityManager.class.getClassLoader(),
            new Class[]{EntityManager.class},
            new PgNotifySpyHandler(original, notifyCalled)
        );

        versionService.entityManager = proxy;
        try {
            versionService.activateVersion(version.id, "operator");
        } finally {
            versionService.entityManager = original;
        }

        assertTrue(notifyCalled.get(), "激活应触发 PostgreSQL NOTIFY");
    }

    private static final class PgNotifySpyHandler implements InvocationHandler {

        private final EntityManager delegate;
        private final AtomicBoolean flag;

        private PgNotifySpyHandler(EntityManager delegate, AtomicBoolean flag) {
            this.delegate = delegate;
            this.flag = flag;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("createNativeQuery".equals(method.getName()) && args != null && args.length > 0) {
                Object sql = args[0];
                if (sql instanceof String && ((String) sql).contains("pg_notify")) {
                    flag.set(true);
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
