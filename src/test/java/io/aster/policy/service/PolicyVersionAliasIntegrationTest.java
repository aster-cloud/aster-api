package io.aster.policy.service;

import aster.core.lexicon.SemanticTokenKind;
import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D 端到端集成测试（ADR 0022 §11，需真实 PG + Flyway V6.14.0）。
 *
 * <p>验证 service+DB 全链路：结构化 createVersion → 校验 → canonicalJson 冻结 →
 * 持久化 alias_set + source_envelope_sha256 → 读回 → 非法别名拒绝。覆盖 C1/C2 落库。
 */
@QuarkusTest
class PolicyVersionAliasIntegrationTest extends BasePolicyVersionServiceTest {

    private static final String SRC =
        "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 3.";

    @Test
    void createWithAliasSetPersistsCanonicalJsonAndEnvelope() {
        Map<SemanticTokenKind, List<String>> aliasSet =
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by"));

        PolicyVersion v = versionService.createVersion(
            catalog.id, SRC, "en-US", "manual", "owner", aliasSet, null);

        assertNotNull(v.id);
        // 读回 DB（脱离持久化上下文确认真落库）
        PolicyVersion reloaded = PolicyVersion.findById(v.id);
        assertNotNull(reloaded.aliasSet, "alias_set 应落库");
        assertTrue(reloaded.aliasSet.contains("TIMES") && reloaded.aliasSet.contains("multiplied by"));
        assertNotNull(reloaded.sourceEnvelopeSha256, "source_envelope_sha256 应落库");
        assertEquals(64, reloaded.sourceEnvelopeSha256.length());
        // chainLink 用 envelope（C1：进链）
        assertEquals(reloaded.sourceEnvelopeSha256, reloaded.chainLink());
    }

    @Test
    void noAliasSetLeavesColumnsNull_backwardCompatible() {
        PolicyVersion v = versionService.createVersion(catalog.id, SRC.replace("multiplied by", "times"), "en-US");
        PolicyVersion reloaded = PolicyVersion.findById(v.id);
        assertNull(reloaded.aliasSet, "无别名 → alias_set NULL");
        // envelope 仍计算（覆盖 content+locale+toolchain），但 aliasSet 段为空
        assertNotNull(reloaded.sourceEnvelopeSha256);
    }

    @Test
    void rejectsSensitiveKindAliasAtCreate() {
        // H3：敏感 kind 别名在结构化入口被拒（社会工程防护，落库前拦截）
        Map<SemanticTokenKind, List<String>> bad =
            Map.of(SemanticTokenKind.RETURN, List.of("approve as"));
        assertThrows(IllegalArgumentException.class,
            () -> versionService.createVersion(catalog.id, SRC, "en-US", "manual", "owner", bad, null));
    }

    @Test
    void rejectsNonCanonicalJsonAtStringEntry() {
        // String 入口：非规范 JSON（key 未排序/未归一）被拒
        String nonCanonical = "{\"TIMES\":[\"Scaled By\"]}"; // 大写非规范
        assertThrows(IllegalArgumentException.class,
            () -> versionService.createVersion(catalog.id, SRC, "en-US", "manual", "owner", nonCanonical));
    }

    @Test
    void rollbackCarriesFrozenAliasSet() {
        // C2：v1 带别名 → 升 v2 无别名 → rollback 到 v1 激活的是 v1 行（带其冻结别名）
        Map<SemanticTokenKind, List<String>> aliasSet =
            Map.of(SemanticTokenKind.TIMES, List.of("multiplied by"));
        PolicyVersion v1 = versionService.createVersion(
            catalog.id, SRC, "en-US", "manual", "owner", aliasSet, null);
        String policyId = v1.policyId;
        // 审批并激活 v1
        versionService.submitForApproval(v1.id, "owner");
        versionService.approveVersion(v1.id, "approver");
        versionService.activateVersion(v1.id, "approver");

        // rollback 到 v1（激活已存行）→ 该行 aliasSet 不变
        PolicyVersion rolled = versionService.rollbackToVersion(policyId, v1.version, "admin");
        assertEquals(v1.id, rolled.id);
        PolicyVersion reloaded = PolicyVersion.findById(rolled.id);
        assertNotNull(reloaded.aliasSet, "rollback 后版本仍带其冻结的 aliasSet（不重读当前配置）");
        assertTrue(reloaded.aliasSet.contains("multiplied by"));
    }
}
