package io.aster.policy.lexicon;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R16-Architectural-5：LexiconAdminMetrics 单元测试。
 *
 * <p>不启动 Quarkus —— 直接 new + 反射注入 SimpleMeterRegistry，
 * 验证 counter 正确递增且 tag 命名合规。
 */
class LexiconAdminMetricsTest {

    private LexiconAdminMetrics metrics;
    private SimpleMeterRegistry registry;

    /**
     * R17-Minor-3：捕获 audit logger 的输出用于格式断言。
     * jboss-logging 在没有 LogManager bridge 时 delegate 给 JUL；
     * 直接拿 JUL Logger 装 Handler 就能拿到记录。
     */
    private List<LogRecord> captured;
    private Handler captureHandler;
    private Logger auditLogger;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SimpleMeterRegistry();
        metrics = new LexiconAdminMetrics();
        var field = LexiconAdminMetrics.class.getDeclaredField("registry");
        field.setAccessible(true);
        field.set(metrics, registry);

        captured = new ArrayList<>();
        auditLogger = Logger.getLogger("io.aster.audit.lexicon");
        captureHandler = new Handler() {
            @Override public void publish(LogRecord r) { captured.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        captureHandler.setLevel(Level.ALL);
        auditLogger.addHandler(captureHandler);
        auditLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void tearDown() {
        if (auditLogger != null && captureHandler != null) {
            auditLogger.removeHandler(captureHandler);
        }
    }

    @Test
    void recordUploadIncrementsCounter() {
        metrics.recordUpload("ok", "zh-CN.jar", "abc123", null, Set.of("zh-CN"));
        Counter c = registry.find("aster_lexicon_upload_total")
            .tag("outcome", "ok")
            .counter();
        assertNotNull(c);
        assertEquals(1.0, c.count());
    }

    @Test
    void recordUploadDifferentOutcomesCreateDifferentCounters() {
        metrics.recordUpload("ok", "zh-CN.jar", "sha1", null, Set.of("zh-CN"));
        metrics.recordUpload("ok", "de-DE.jar", "sha2", null, Set.of("de-DE"));
        metrics.recordUpload("strict_drift_rejected", "ja-JP.jar", "sha3", null, Set.of());
        metrics.recordUpload("backup_restore_load_failed", "fr-FR.jar", "sha4",
            "rec-1", null);

        assertEquals(2.0, registry.find("aster_lexicon_upload_total")
            .tag("outcome", "ok").counter().count());
        assertEquals(1.0, registry.find("aster_lexicon_upload_total")
            .tag("outcome", "strict_drift_rejected").counter().count());
        assertEquals(1.0, registry.find("aster_lexicon_upload_total")
            .tag("outcome", "backup_restore_load_failed").counter().count());
    }

    @Test
    void recordDeleteIncrementsCounter() {
        metrics.recordDelete("removed", "zh-CN.jar");
        metrics.recordDelete("not_found", "missing.jar");
        metrics.recordDelete("removed", "de-DE.jar");

        assertEquals(2.0, registry.find("aster_lexicon_delete_total")
            .tag("outcome", "removed").counter().count());
        assertEquals(1.0, registry.find("aster_lexicon_delete_total")
            .tag("outcome", "not_found").counter().count());
    }

    @Test
    void nullOutcomeMappedToUnknownTag() {
        // 防御性：调用方传 null outcome 不应崩；归为 "unknown" tag
        metrics.recordUpload(null, "broken.jar", "sha", null, null);
        assertEquals(1.0, registry.find("aster_lexicon_upload_total")
            .tag("outcome", "unknown").counter().count());
    }

    // ============================================================
    // R17-Major-1: availability change (enable/disable) instrumentation
    // ============================================================

    @Test
    void recordAvailabilityChangeIncrementsCounter() {
        metrics.recordAvailabilityChange("enable", "enabled", "zh-CN");
        metrics.recordAvailabilityChange("disable", "disabled", "zh-CN");
        metrics.recordAvailabilityChange("enable", "unchanged", "zh-CN");

        assertEquals(1.0, registry.find("aster_lexicon_availability_total")
            .tag("op", "enable").tag("outcome", "enabled").counter().count());
        assertEquals(1.0, registry.find("aster_lexicon_availability_total")
            .tag("op", "disable").tag("outcome", "disabled").counter().count());
        assertEquals(1.0, registry.find("aster_lexicon_availability_total")
            .tag("op", "enable").tag("outcome", "unchanged").counter().count());
    }

    // ============================================================
    // R17-Minor-3: audit log format assertions
    // ============================================================

    @Test
    void auditLogIncludesAllFieldsForSuccessfulUpload() {
        metrics.recordUpload("ok", "zh-CN.jar", "abc123def", null, Set.of("zh-CN"));
        assertEquals(1, captured.size());
        String msg = captured.get(0).getMessage();
        assertTrue(msg.startsWith("audit.lexicon op=upload"), "msg=" + msg);
        assertTrue(msg.contains(" outcome=ok"), "msg=" + msg);
        assertTrue(msg.contains(" actor=hmac"), "msg=" + msg);
        assertTrue(msg.contains(" fileName=zh-CN.jar"), "msg=" + msg);
        assertTrue(msg.contains(" sha256=abc123def"), "msg=" + msg);
        assertTrue(msg.contains(" registered=zh-CN"), "msg=" + msg);
        // R17-Minor-2: registered 不应含空格（Set.toString 会产生 "[en-US, de-DE]"）
        assertTrue(!msg.contains("[zh-CN]"), "registered 应 join 成 zh-CN 而非 [zh-CN]：" + msg);
    }

    @Test
    void auditLogSerializesRegisteredAsCommaJoinedWithoutSpaces() {
        // R17-Minor-2 防回归：多 locale 必须用逗号分隔且无空格
        metrics.recordUpload("ok", "multi.jar", "sha", null,
            new java.util.LinkedHashSet<>(java.util.List.of("zh-CN", "de-DE", "en-US")));
        String msg = captured.get(0).getMessage();
        assertTrue(msg.contains(" registered=zh-CN,de-DE,en-US"),
            "应为 zh-CN,de-DE,en-US（无空格、无方括号）：" + msg);
        assertTrue(!msg.contains(", "), "audit 行不应含 ', '（破坏 Loki token 切分）：" + msg);
    }

    @Test
    void auditLogOmitsOptionalFieldsWhenNull() {
        metrics.recordUpload("strict_drift_rejected", "rejected.jar", "sha", null, java.util.Set.of());
        String msg = captured.get(0).getMessage();
        assertTrue(msg.contains(" sha256="), "sha256 应在");
        assertTrue(!msg.contains("recoveryId="), "无 recoveryId 时不应写空字段：" + msg);
        assertTrue(!msg.contains("registered="), "空 registered 不应写：" + msg);
    }

    @Test
    void auditLogIncludesRecoveryId() {
        metrics.recordUpload("backup_restore_load_failed", "boom.jar", "sha",
            "rec-uuid-123", null);
        String msg = captured.get(0).getMessage();
        assertTrue(msg.contains(" recoveryId=rec-uuid-123"), "msg=" + msg);
    }

    @Test
    void auditLogNullOutcomeNormalizedToUnknown() {
        // R17-Minor-2：null outcome 在 audit 里也应该是 "unknown" 而非字面 "null"
        metrics.recordUpload(null, "weird.jar", "sha", null, null);
        String msg = captured.get(0).getMessage();
        assertTrue(msg.contains(" outcome=unknown"),
            "audit 行 null outcome 应归一化为 unknown：" + msg);
        assertTrue(!msg.contains(" outcome=null"),
            "不应出现字面 'outcome=null'：" + msg);
    }

    @Test
    void auditLogForAvailabilityChangeIncludesLocaleId() {
        // R18-Major-1：locale ID 走显式 localeId= 字段，不再用 fileName= 槽位
        metrics.recordAvailabilityChange("disable", "disabled", "zh-CN");
        String msg = captured.get(0).getMessage();
        assertTrue(msg.startsWith("audit.lexicon op=disable"), "msg=" + msg);
        assertTrue(msg.contains(" outcome=disabled"), "msg=" + msg);
        assertTrue(msg.contains(" localeId=zh-CN"),
            "R18-Major-1：应使用显式 localeId= 字段：" + msg);
        // R18-Major-1 regression guard: 不应再有 fileName=zh-CN
        assertTrue(!msg.contains(" fileName="),
            "availability change 不应写 fileName= 字段（误导下游）：" + msg);
    }

    @Test
    void auditLogForUploadStillUsesFileNameNotLocaleId() {
        // R18-Major-1 regression guard: upload 仍走 fileName=，不写 localeId=
        metrics.recordUpload("ok", "zh-CN.jar", "sha", null, java.util.Set.of("zh-CN"));
        String msg = captured.get(0).getMessage();
        assertTrue(msg.contains(" fileName=zh-CN.jar"), "msg=" + msg);
        assertTrue(!msg.contains(" localeId="),
            "upload 不应写 localeId= 字段（fileName 才是规范字段）：" + msg);
    }
}
