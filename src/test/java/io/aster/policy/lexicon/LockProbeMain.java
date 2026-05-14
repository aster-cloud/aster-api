package io.aster.policy.lexicon;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * R14-Major：跨进程 FileLock 探针。仅用于测试。
 *
 * <p>用法（{@link HotPlugLexiconLoaderCrossProcessLockIT} 启动子进程时）：
 * <pre>
 *   java -cp ... io.aster.policy.lexicon.LockProbeMain &lt;path-to-lockfile&gt;
 * </pre>
 *
 * <p>行为：
 * <ol>
 *   <li>打开 lockfile 路径（CREATE + WRITE）</li>
 *   <li>{@code tryLock()}</li>
 *   <li>成功 → 打印 {@code ACQUIRED} 到 stdout 并退出 0</li>
 *   <li>{@code tryLock()} 返回 null → 打印 {@code DENIED} 并退出 0</li>
 *   <li>{@link OverlappingFileLockException} → 打印 {@code DENIED} 并退出 0（理论上跨进程不会发生）</li>
 *   <li>其他异常 → 打印 {@code ERROR:&lt;message&gt;} 到 stderr 并退出 1</li>
 * </ol>
 *
 * <p>子进程立即退出 —— 拿到锁后不持有，否则父进程的 `process.waitFor()` 会挂死。
 * 父进程的真正验证：在父进程持有锁期间启动子进程，子进程应当看到 DENIED。
 */
public final class LockProbeMain {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("ERROR:missing-path-arg");
            System.exit(1);
        }
        Path lockPath = Path.of(args[0]);
        try (FileChannel ch = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock fl;
            try {
                fl = ch.tryLock();
            } catch (OverlappingFileLockException ovr) {
                System.out.println("DENIED");
                return;
            }
            if (fl == null) {
                System.out.println("DENIED");
            } else {
                System.out.println("ACQUIRED");
                fl.release();
            }
        } catch (Exception e) {
            System.err.println("ERROR:" + e.getClass().getSimpleName() + ":" + e.getMessage());
            System.exit(1);
        }
    }

    private LockProbeMain() {}
}
