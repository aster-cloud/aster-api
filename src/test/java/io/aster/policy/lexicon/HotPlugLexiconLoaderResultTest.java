package io.aster.policy.lexicon;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R9-Backend-M3 + M5：LoadResult 状态码 / ok() 语义单元测试。
 *
 * <p>不需要 Quarkus 上下文 —— 纯 record 行为校验。
 */
class HotPlugLexiconLoaderResultTest {

    @Test
    void okOutcomeReportsSuccess() {
        var r = new HotPlugLexiconLoader.LoadResult("ok", "loaded", Set.of("zh-CN"));
        assertTrue(r.ok());
        assertEquals(200, r.httpStatus());
    }

    @Test
    void unchangedOutcomeAlsoReportsSuccess() {
        // R9-Backend-M3：watcher 抢先加载导致 sync 路径返回 unchanged，
        // 但 jar 已经在内存里 → 视为成功，避免误报 422
        var r = new HotPlugLexiconLoader.LoadResult(
            "unchanged", "already loaded", Set.of("zh-CN"));
        assertTrue(r.ok(), "unchanged 应被视为 ok（jar 已加载至请求 sha256）");
        assertEquals(200, r.httpStatus());
    }

    @Test
    void strictDriftMapsTo422() {
        // R9-Backend-M5：客户端错误（jar 内容问题），返回 422
        var r = new HotPlugLexiconLoader.LoadResult(
            "strict_drift_rejected", "lost=[zh-HK]", Set.of("zh-CN"));
        assertFalse(r.ok());
        assertEquals(422, r.httpStatus());
    }

    @Test
    void noPluginMetadataMapsTo422() {
        var r = new HotPlugLexiconLoader.LoadResult(
            "no_plugin_metadata", "providedLexiconIds empty", Set.of());
        assertEquals(422, r.httpStatus());
    }

    @Test
    void ioErrorMapsTo503() {
        // R9-Backend-M5：服务端瞬时故障，客户端可重试
        var r = new HotPlugLexiconLoader.LoadResult(
            "io_error", "disk full", Set.of());
        assertFalse(r.ok());
        assertEquals(503, r.httpStatus());
    }

    @Test
    void classloaderErrorMapsTo503() {
        var r = new HotPlugLexiconLoader.LoadResult(
            "classloader_error", "URL construct failed", Set.of());
        assertEquals(503, r.httpStatus());
    }

    @Test
    void notRegularFileMapsTo422() {
        // R10-Backend-M5：上传了非常规文件 —— 客户端问题，重试无意义
        var r = new HotPlugLexiconLoader.LoadResult(
            "not_regular_file", "uploaded path is a directory", Set.of());
        assertEquals(422, r.httpStatus());
    }

    @Test
    void previewErrorMapsTo422() {
        // R10-Backend-M5：plugin providedLexiconIds() 抛错 —— plugin 元数据 bug
        var r = new HotPlugLexiconLoader.LoadResult(
            "preview_error", "providedLexiconIds threw NullPointerException", Set.of());
        assertEquals(422, r.httpStatus());
    }

    @Test
    void backupRestoreLoadFailedMapsTo500() {
        // R10-Backend-C1：备份恢复后加载失败 —— half-state，需人工介入
        var r = new HotPlugLexiconLoader.LoadResult(
            "backup_restore_load_failed",
            "new rejected AND backup reload failed",
            Set.of());
        assertEquals(500, r.httpStatus());
    }

    @Test
    void backupRestoreIoFailedMapsTo500() {
        var r = new HotPlugLexiconLoader.LoadResult(
            "backup_restore_io_failed",
            "Files.move backup→canonical threw",
            Set.of());
        assertEquals(500, r.httpStatus());
    }

    @Test
    void rollbackFailedMapsTo500() {
        // R9-Backend-C2：half-state，需要人工介入
        var r = new HotPlugLexiconLoader.LoadResult(
            "rollback_failed",
            "load rejected AND rollback failed",
            Set.of("en-US"));
        assertFalse(r.ok());
        assertEquals(500, r.httpStatus());
    }

    @Test
    void unknownOutcomeMapsTo500() {
        var r = new HotPlugLexiconLoader.LoadResult(
            "future_outcome_unknown_to_this_version", "", Set.of());
        assertEquals(500, r.httpStatus());
    }

    @Test
    void okMessageNeverEmptyForNonOk() {
        // 防回归：non-ok 必须带 message（便于诊断）
        var r = new HotPlugLexiconLoader.LoadResult(
            "discover-failed", "tx threw at plugin X", Set.of());
        assertFalse(r.message().isBlank());
    }
}
