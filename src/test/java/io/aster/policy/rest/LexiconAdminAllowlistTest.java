package io.aster.policy.rest;

import io.aster.policy.rest.LexiconAdminResource.AllowlistOutcome;
import io.aster.policy.rest.LexiconAdminResource.AllowlistVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SHA-256 allowlist 纵深防御判定单测（ADR 0018 加固）。
 *
 * <p>不启动 Quarkus —— 直接调纯静态方法 {@link LexiconAdminResource#checkAllowlist}，
 * 验证 fail-closed（生产）/fail-open（dev/test）/命中/未命中四类判定。
 *
 * <p>核心契约：HMAC 是第一道门（默认强制），allowlist 是 HMAC key 泄露后的纵深兜底。
 * 生产 profile（require-allowlist=true）下空 allowlist 必须拒绝上传，而非放行。
 */
@DisplayName("LexiconAdmin SHA-256 allowlist 判定")
class LexiconAdminAllowlistTest {

    /** 一个示例 jar 的 SHA-256 hex（值本身无所谓，仅作判定输入）。 */
    private static final String SHA =
        "abc123def456abc123def456abc123def456abc123def456abc123def456abcd";
    private static final String OTHER_SHA =
        "0000000000000000000000000000000000000000000000000000000000000000";

    // ---------- fail-closed：生产 profile（require-allowlist=true）

    @Test
    @DisplayName("生产 + 空 allowlist → REQUIRED（拒绝上传，纵深兜底）")
    void prodEmptyAllowlistRejected() {
        assertThat(LexiconAdminResource.checkAllowlist(null, true, SHA).outcome())
            .isEqualTo(AllowlistOutcome.REQUIRED);
        assertThat(LexiconAdminResource.checkAllowlist("", true, SHA).outcome())
            .isEqualTo(AllowlistOutcome.REQUIRED);
        assertThat(LexiconAdminResource.checkAllowlist("   ", true, SHA).outcome())
            .isEqualTo(AllowlistOutcome.REQUIRED);
    }

    @Test
    @DisplayName("生产 + allowlist 命中 → ALLOWED")
    void prodAllowlistMatchAllowed() {
        assertThat(LexiconAdminResource.checkAllowlist(SHA, true, SHA).outcome())
            .isEqualTo(AllowlistOutcome.ALLOWED);
    }

    @Test
    @DisplayName("生产 + allowlist 未命中 → NOT_LISTED")
    void prodAllowlistMissNotListed() {
        assertThat(LexiconAdminResource.checkAllowlist(OTHER_SHA, true, SHA).outcome())
            .isEqualTo(AllowlistOutcome.NOT_LISTED);
    }

    // ---------- fail-open：dev/test profile（require-allowlist=false）

    @Test
    @DisplayName("dev/test + 空 allowlist → ALLOWED（fail-open，开发便利）")
    void devEmptyAllowlistAllowed() {
        assertThat(LexiconAdminResource.checkAllowlist(null, false, SHA).outcome())
            .isEqualTo(AllowlistOutcome.ALLOWED);
        assertThat(LexiconAdminResource.checkAllowlist("", false, SHA).outcome())
            .isEqualTo(AllowlistOutcome.ALLOWED);
    }

    @Test
    @DisplayName("dev/test + allowlist 已配但未命中 → 仍按 allowlist 校验 NOT_LISTED")
    void devConfiguredAllowlistStillEnforced() {
        // require-allowlist=false 只放宽"空 allowlist"，一旦配置了 allowlist 仍严格校验。
        assertThat(LexiconAdminResource.checkAllowlist(OTHER_SHA, false, SHA).outcome())
            .isEqualTo(AllowlistOutcome.NOT_LISTED);
    }

    // ---------- 既有行为不变：多条目、大小写、空白

    @Test
    @DisplayName("逗号分隔多条目命中其一 → ALLOWED")
    void multiEntryMatch() {
        String list = OTHER_SHA + "," + SHA + ",deadbeef";
        assertThat(LexiconAdminResource.checkAllowlist(list, true, SHA).outcome())
            .isEqualTo(AllowlistOutcome.ALLOWED);
    }

    @Test
    @DisplayName("条目含空白 + 大小写不敏感命中")
    void trimAndCaseInsensitive() {
        String list = "  " + SHA.toUpperCase() + "  , " + OTHER_SHA;
        assertThat(LexiconAdminResource.checkAllowlist(list, true, SHA).outcome())
            .isEqualTo(AllowlistOutcome.ALLOWED);
    }

    @Test
    @DisplayName("AllowlistVerdict 是不可变 record")
    void verdictRecordEquality() {
        assertThat(new AllowlistVerdict(AllowlistOutcome.ALLOWED))
            .isEqualTo(new AllowlistVerdict(AllowlistOutcome.ALLOWED));
    }
}
