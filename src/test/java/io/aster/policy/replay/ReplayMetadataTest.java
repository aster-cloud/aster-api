package io.aster.policy.replay;

import aster.core.canonical.CanonicalJson;
import io.aster.policy.api.model.DecisionTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReplayMetadata 单元测试（ADR 0030 附录 A 回放地基）。
 *
 * <p>回放地基的正确性铁律：漂移检测靠 hash，hash 必须
 * <ol>
 *   <li>确定性——同输入必得同 hash（否则每次评估都误报漂移）；</li>
 *   <li>剔非决定性——traceHash 不含 executionTimeMs（否则同一决策因耗时抖动误报漂移）；</li>
 *   <li>敏感于业务变化——result 变则 outputHash 变（否则漏报真漂移）；</li>
 *   <li>版本自证——canonicalizationVersion 与 CanonicalJson 版本一致（跨引擎 parity 前提）。</li>
 * </ol>
 */
class ReplayMetadataTest {

    private static final String TOOLCHAIN = "abi=V1;core=1.0.8;validator=3;build=test";

    private static DecisionTrace trace(Object result, long execTimeMs) {
        return new DecisionTrace("aster.finance.loan", "approveLoan", List.of(), result, execTimeMs);
    }

    @Test
    @DisplayName("确定性：同输入同输出同 trace → 逐字段 hash 相等")
    void deterministic() {
        Object input = Map.of("creditScore", 680, "income", 50000);
        Object result = "APPROVED";
        ReplayMetadata a = ReplayMetadata.compute(TOOLCHAIN, input, result, trace(result, 5));
        ReplayMetadata b = ReplayMetadata.compute(TOOLCHAIN, input, result, trace(result, 5));

        assertEquals(a.canonicalInputHash(), b.canonicalInputHash());
        assertEquals(a.canonicalOutputHash(), b.canonicalOutputHash());
        assertEquals(a.traceHash(), b.traceHash());
        assertEquals(a.runtimeToolchainId(), b.runtimeToolchainId());
    }

    @Test
    @DisplayName("traceHash 剔除 executionTimeMs：同决策不同耗时 → traceHash 相等")
    void traceHashExcludesExecutionTime() {
        Object result = "REFER";
        ReplayMetadata fast = ReplayMetadata.compute(TOOLCHAIN, Map.of("x", 1), result, trace(result, 3));
        ReplayMetadata slow = ReplayMetadata.compute(TOOLCHAIN, Map.of("x", 1), result, trace(result, 999));
        assertEquals(fast.traceHash(), slow.traceHash(),
            "同一决策不应因执行耗时抖动而产生不同 traceHash");
    }

    @Test
    @DisplayName("输出敏感：result 变则 canonicalOutputHash 变（漏报真漂移是致命的）")
    void outputHashSensitiveToResult() {
        Object input = Map.of("creditScore", 680);
        ReplayMetadata approved = ReplayMetadata.compute(TOOLCHAIN, input, "APPROVED", trace("APPROVED", 5));
        ReplayMetadata refer = ReplayMetadata.compute(TOOLCHAIN, input, "REFER", trace("REFER", 5));
        assertNotEquals(approved.canonicalOutputHash(), refer.canonicalOutputHash());
        // 决策不同 → trace 的 finalResult 不同 → traceHash 也应不同。
        assertNotEquals(approved.traceHash(), refer.traceHash());
    }

    @Test
    @DisplayName("输入敏感：context 变则 canonicalInputHash 变（1 分翻转须可辨）")
    void inputHashSensitiveToContext() {
        ReplayMetadata s680 = ReplayMetadata.compute(TOOLCHAIN, Map.of("creditScore", 680), "APPROVED", trace("APPROVED", 5));
        ReplayMetadata s679 = ReplayMetadata.compute(TOOLCHAIN, Map.of("creditScore", 679), "REFER", trace("REFER", 5));
        assertNotEquals(s680.canonicalInputHash(), s679.canonicalInputHash());
    }

    @Test
    @DisplayName("键序无关：Map 键顺序不影响 inputHash（canonical 排序）")
    void inputHashIndependentOfKeyOrder() {
        // 用 LinkedHashMap 强制不同插入顺序，canonical 排序后应相等。
        var m1 = new java.util.LinkedHashMap<String, Object>();
        m1.put("a", 1);
        m1.put("b", 2);
        var m2 = new java.util.LinkedHashMap<String, Object>();
        m2.put("b", 2);
        m2.put("a", 1);
        ReplayMetadata r1 = ReplayMetadata.compute(TOOLCHAIN, m1, "OK", trace("OK", 1));
        ReplayMetadata r2 = ReplayMetadata.compute(TOOLCHAIN, m2, "OK", trace("OK", 1));
        assertEquals(r1.canonicalInputHash(), r2.canonicalInputHash());
    }

    @Test
    @DisplayName("canonicalizationVersion 与 CanonicalJson 版本一致（跨引擎 parity 前提）")
    void versionMatchesCanonicalJson() {
        ReplayMetadata rm = ReplayMetadata.compute(TOOLCHAIN, Map.of("x", 1), "OK", trace("OK", 1));
        assertEquals(CanonicalJson.CANONICALIZATION_VERSION, rm.canonicalizationVersion());
        assertEquals(CanonicalJson.CANONICALIZATION_VERSION, ReplayMetadata.CANONICALIZATION_VERSION);
    }

    @Test
    @DisplayName("null 输入 → inputHash 为 null；null trace → traceHash 为 null（回放非必需字段可缺）")
    void nullsProduceNullHashes() {
        ReplayMetadata rm = ReplayMetadata.compute(TOOLCHAIN, null, "OK", null);
        assertNull(rm.canonicalInputHash());
        assertNull(rm.traceHash());
        // outputHash 始终计算（result 非 null）。
        assertTrue(rm.canonicalOutputHash() != null && !rm.canonicalOutputHash().isBlank());
    }

    @Test
    @DisplayName("reasonCodes M1 为空 []（引擎不产结构化 reason，业务字段不自动抽取）")
    void reasonCodesEmptyInM1() {
        ReplayMetadata rm = ReplayMetadata.compute(TOOLCHAIN, Map.of("x", 1), "OK", trace("OK", 1));
        assertTrue(rm.reasonCodes().isEmpty());
    }

    // ---- Codex 复审 P0：Decimal 输入不得使回放地基失效（金融金额高频小数） ----

    @Test
    @DisplayName("Decimal 输入：小数 context 仍产 REPLAYABLE + 非空 inputHash（不再抛/静默丢）")
    void decimalInputIsReplayable() {
        Object input = Map.of("amount", new java.math.BigDecimal("100.50"), "score", 680);
        ReplayMetadata rm = ReplayMetadata.compute(TOOLCHAIN, input, "APPROVED", trace("APPROVED", 5));
        assertEquals(ReplayMetadata.STATUS_REPLAYABLE, rm.replayabilityStatus(),
            "小数输入应走 string-lift 正常 hash，不应降级");
        assertNotNull(rm.canonicalInputHash());
        assertTrue(rm.replayabilityReasons().isEmpty());
    }

    @Test
    @DisplayName("Decimal 确定性：同小数值不同 BigDecimal scale/表述 → 同 inputHash")
    void decimalHashDeterministicAcrossScale() {
        // 100.50 与 100.5 数值相等，toPlainString 归一后应同 hash（canonicalDecimal 去 trailing zero）。
        ReplayMetadata a = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("amount", new java.math.BigDecimal("100.50")), "OK", trace("OK", 1));
        ReplayMetadata b = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("amount", new java.math.BigDecimal("100.5")), "OK", trace("OK", 1));
        assertEquals(a.canonicalInputHash(), b.canonicalInputHash());
    }

    @Test
    @DisplayName("Decimal 敏感：0.30 vs 0.31 → inputHash 不同（金额精度须可辨）")
    void decimalHashSensitive() {
        ReplayMetadata a = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("rate", new java.math.BigDecimal("0.30")), "OK", trace("OK", 1));
        ReplayMetadata b = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("rate", new java.math.BigDecimal("0.31")), "OK", trace("OK", 1));
        assertNotEquals(a.canonicalInputHash(), b.canonicalInputHash());
    }

    @Test
    @DisplayName("Decimal 业务输出：result 为 BigDecimal 小数 → outputHash 成功 + REPLAYABLE")
    void decimalResultIsReplayable() {
        ReplayMetadata rm = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("x", 1), new java.math.BigDecimal("0.30"),
            trace(new java.math.BigDecimal("0.30"), 5));
        assertEquals(ReplayMetadata.STATUS_REPLAYABLE, rm.replayabilityStatus());
        assertNotNull(rm.canonicalOutputHash());
    }

    @Test
    @DisplayName("嵌套 Decimal：数组/嵌套对象里的小数也走 string-lift（路径正确）")
    void nestedDecimalLifted() {
        Object input = Map.of(
            "items", List.of(
                Map.of("price", new java.math.BigDecimal("9.99")),
                Map.of("price", new java.math.BigDecimal("19.95"))));
        ReplayMetadata rm = ReplayMetadata.compute(TOOLCHAIN, input, "OK", trace("OK", 1));
        assertEquals(ReplayMetadata.STATUS_REPLAYABLE, rm.replayabilityStatus());
        assertNotNull(rm.canonicalInputHash());
    }

    @Test
    @DisplayName("超 safe-integer 整数：> 2^53−1 也 string-lift（不抛不降级）")
    void oversizeIntegerLifted() {
        // 9007199254740993 = 2^53 + 1（超 JS safe-integer），须 string 承载。
        ReplayMetadata rm = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("bigId", new java.math.BigInteger("9007199254740993")),
            "OK", trace("OK", 1));
        assertEquals(ReplayMetadata.STATUS_REPLAYABLE, rm.replayabilityStatus());
        assertNotNull(rm.canonicalInputHash());
    }

    @Test
    @DisplayName("成功路径 status=REPLAYABLE；纯整数/字符串输入不触发降级")
    void plainInputIsReplayable() {
        ReplayMetadata rm = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("score", 680, "tier", "gold"), "APPROVED", trace("APPROVED", 5));
        assertEquals(ReplayMetadata.STATUS_REPLAYABLE, rm.replayabilityStatus());
        assertTrue(rm.replayabilityReasons().isEmpty());
    }

    // ---- Codex 复审 P0：fail-loud（真正无法 hash 的值不静默丢，落 NON_REPLAYABLE + reason） ----

    @Test
    @DisplayName("非 finite 输出（NaN）：outputHash 失败 → NON_REPLAYABLE + 记 output 原因，不静默成功")
    void nonFiniteOutputIsNonReplayable() {
        // Double.NaN 无法 canonical（跨引擎/JSON 无 NaN）——output 是必需字段，必须 fail-loud。
        ReplayMetadata rm = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("x", 1), Double.NaN, trace("NaN", 5));
        assertEquals(ReplayMetadata.STATUS_NON_REPLAYABLE, rm.replayabilityStatus());
        assertNull(rm.canonicalOutputHash());
        assertTrue(rm.replayabilityReasons().stream().anyMatch(r -> r.startsWith("output_hash_failed")),
            "必须记录 output hash 失败原因，让调用方知道没拿到地基：" + rm.replayabilityReasons());
    }

    @Test
    @DisplayName("非 finite 输入（Infinity）：inputHash 失败 → NON_REPLAYABLE，但 outputHash 仍算出")
    void nonFiniteInputIsNonReplayable() {
        ReplayMetadata rm = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("ratio", Double.POSITIVE_INFINITY), "OK", trace("OK", 5));
        assertEquals(ReplayMetadata.STATUS_NON_REPLAYABLE, rm.replayabilityStatus());
        assertNull(rm.canonicalInputHash());
        assertNotNull(rm.canonicalOutputHash(), "output 与 input 独立，output 不应因 input 失败而丢");
        assertTrue(rm.replayabilityReasons().stream().anyMatch(r -> r.startsWith("input_hash_failed")));
    }

    // ---- Codex 复审 P0#1：数组内 Decimal 必须走 decimal 路径，不得与 string 碰撞 ----

    @Test
    @DisplayName("数组内 Decimal ≠ string：[1.5] 与 [\"1.5\"] 必须产不同 inputHash（回放地基正确性）")
    void arrayDecimalDoesNotCollideWithString() {
        // TypeContext.of 对 array 下标 [n]→[] 归一，decimalPath 才能命中；否则 lift 出的
        // "1.5" 被当普通 JSON string，与真 string "1.5" 碰撞（已实证的回放地基级 bug）。
        ReplayMetadata asDecimal = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("v", List.of(new java.math.BigDecimal("1.5"))), "OK", trace("OK", 1));
        ReplayMetadata asString = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("v", List.of("1.5")), "OK", trace("OK", 1));
        assertNotEquals(asDecimal.canonicalInputHash(), asString.canonicalInputHash(),
            "数组内 Decimal 1.5 与 string \"1.5\" 语义不同，hash 必须不同");
    }

    @Test
    @DisplayName("嵌套数组 Decimal 确定性：数组内同小数值不同 scale → 同 hash（走 decimal canonical）")
    void arrayDecimalDeterministicAcrossScale() {
        ReplayMetadata a = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("v", List.of(new java.math.BigDecimal("9.90"))), "OK", trace("OK", 1));
        ReplayMetadata b = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("v", List.of(new java.math.BigDecimal("9.9"))), "OK", trace("OK", 1));
        assertEquals(a.canonicalInputHash(), b.canonicalInputHash(),
            "数组内 9.90==9.9 应经 decimal canonical 去 trailing zero 后同 hash");
    }

    // ---- Codex 复审 P0#3：大指数 Decimal 不得提前巨量展开（DoS 防护） ----

    @Test
    @DisplayName("大指数 Decimal（1E+1000000）：不 OOM，落 NON_REPLAYABLE（canonicalDecimal 指数上限先拒）")
    void hugeExponentDecimalIsRejectedNotExpanded() {
        // toString()=\"1E+1000000\"（紧凑），canonicalDecimal 按指数长度/MAX_DECIMAL_EXPONENT
        // 在展开前拒绝 → DECIMAL_TOO_LARGE → tryHash 记原因 → NON_REPLAYABLE。
        // 若用 toPlainString() 会先构造百万位字符串（DoS）。此测试证明不发生。
        ReplayMetadata rm = ReplayMetadata.compute(
            TOOLCHAIN, Map.of("huge", new java.math.BigDecimal("1E+1000000")), "OK", trace("OK", 1));
        assertEquals(ReplayMetadata.STATUS_NON_REPLAYABLE, rm.replayabilityStatus());
        assertNull(rm.canonicalInputHash());
        assertTrue(rm.replayabilityReasons().stream().anyMatch(r -> r.startsWith("input_hash_failed")),
            "大指数应被 canonicalDecimal 上限拒绝并记原因：" + rm.replayabilityReasons());
        // output 独立正常。
        assertNotNull(rm.canonicalOutputHash());
    }
}
