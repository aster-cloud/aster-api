package io.aster.policy.lexicon;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14-Major + R15-Minor-2/Suggestion-4：真正的跨进程 FileLock 测试。
 *
 * <p>R13 单测在同一 JVM 内用两个 FileChannel 验证互斥 —— Java 层先抛
 * {@link java.nio.channels.OverlappingFileLockException}，未走到 OS fcntl。本 IT
 * 通过 ProcessBuilder fork 一个子 JVM 跑 {@link LockProbeMain}，真正触发跨进程
 * POSIX 锁路径。
 *
 * <p>用 {@code *IT.java} 命名 —— Gradle 的 {@code integrationTest} 任务跑这个，
 * 默认 {@code test} 不跑（避免每次单测都 fork JVM 的代价）。
 */
class HotPlugLexiconLoaderCrossProcessLockIT {

    // R16-Suggestion-4: 30s 在繁忙 CI runner 上偶尔 cold-JVM startup 紧张；60s 给出余量
    // 又远小于 Gradle 任务级别超时，不会让 hanging 测试拖整个 build。
    private static final int CHILD_TIMEOUT_SECONDS = 60;

    /**
     * R15-Minor-2 + R16-Suggestion-4：fork 一个子进程跑 {@link LockProbeMain}，
     * 完整捕获 stdout/stderr/exit code/timeout 用于诊断。
     *
     * <p>I/O 顺序：先读 stdout，再读 stderr，最后 {@code exitValue()}。安全前提：
     * {@link LockProbeMain} 每次最多向 stdout 或 stderr 写一行短文本（&lt; 200 字节），
     * 远小于 pipe buffer，子进程不会阻塞在 flush 上、父进程不会因为 stream 顺序产生死锁。
     * 若将来扩展 LockProbeMain 输出量，需改用并发线程读两条 stream。
     */
    private record ProbeResult(String stdout, String stderr, int exitCode) {}

    private static ProbeResult forkProbe(Path lockPath) throws Exception {
        // R15-Suggestion-4：用 Path.of 而非字符串拼接，跨平台
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
            javaBin,
            "-cp", System.getProperty("java.class.path"),
            LockProbeMain.class.getName(),
            lockPath.toString());
        pb.redirectErrorStream(false);
        Process child = pb.start();

        boolean exited = child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            child.destroyForcibly();
            throw new AssertionError("child process did not exit within "
                + CHILD_TIMEOUT_SECONDS + "s");
        }

        String stdout;
        try (var br = new BufferedReader(new InputStreamReader(
                child.getInputStream(), StandardCharsets.UTF_8))) {
            stdout = br.readLine();
        }
        StringBuilder errBuf = new StringBuilder();
        try (var br = new BufferedReader(new InputStreamReader(
                child.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) errBuf.append(line).append('\n');
        }
        return new ProbeResult(stdout, errBuf.toString(), child.exitValue());
    }

    @Test
    void anotherProcessTryLockReturnsDeniedWhileWeHold() throws Exception {
        Path tmp = Files.createTempDirectory("aster-cross-process-lock-");
        try {
            Path lockPath = tmp.resolve("zh-CN.jar.cross-lock");
            // 父进程持有 lock
            try (var ch = java.nio.channels.FileChannel.open(
                    lockPath,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
                 var fl = ch.tryLock()) {
                assertTrue(fl != null, "parent should acquire the lock first");

                ProbeResult result = forkProbe(lockPath);
                assertEquals(0, result.exitCode(),
                    "child exit code 应 0；stderr=" + result.stderr());
                assertEquals("DENIED", result.stdout(),
                    "child tryLock 必须返回 DENIED（parent 持有锁）；stderr=" + result.stderr());
            }

            // R15-Minor-2: 第二次 fork 也走完整诊断捕获路径
            ProbeResult after = forkProbe(lockPath);
            assertEquals(0, after.exitCode(),
                "second fork exit code 应 0；stderr=" + after.stderr());
            assertEquals("ACQUIRED", after.stdout(),
                "parent 释放后新 fork 必须 ACQUIRED；stderr=" + after.stderr());
        } finally {
            try (var stream = Files.walk(tmp)) {
                stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }
}
