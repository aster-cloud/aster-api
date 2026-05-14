package io.aster.policy.lexicon;

import aster.core.lexicon.LexiconRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 监视 lexicon jar 目录并热加载新发现的语言包。
 *
 * <p><b>关键设计</b>：
 * <ul>
 *   <li>Jar 通过新建的 {@link URLClassLoader} 显式传给
 *       {@link LexiconRegistry#discoverPlugins(ClassLoader)}，<b>不再污染调用线程的
 *       contextClassLoader</b>（修正 M4）</li>
 *   <li>每个 jar 用 SHA-256 内容哈希 + 文件名作为身份，所以**同名不同内容**会被
 *       识别为 replace 而非"已加载，跳过"（修正 M5）</li>
 *   <li>WatchService 只识别 {@code *.jar} 的最终态；{@code *.part} 中间态被忽略，
 *       上传方必须 atomic rename（修正 M6）</li>
 *   <li>失败/零插件加载 <b>不写入</b> {@code loadedJars}，避免后续 MODIFY 被错跳过</li>
 *   <li>{@link URLClassLoader} 在加载失败、jar replace、ShutdownEvent 时显式 close
 *       （修正 M7）。注意：软下线时 loader 暂保留，等运维显式删 jar 再 close</li>
 * </ul>
 *
 * <p><b>安全</b>：本类不做 jar 签名验证；前置签名/完整性校验在
 * {@code LexiconAdminResource} 完成（C2/C3）。本类信任目录里的 jar 已是受信件。
 */
@ApplicationScoped
public class HotPlugLexiconLoader {

    private static final Logger LOG = Logger.getLogger(HotPlugLexiconLoader.class);

    @ConfigProperty(name = "aster.lexicon.hotplug.dir",
        defaultValue = "/var/aster/lexicons/jars")
    String hotplugDir;

    @ConfigProperty(name = "aster.lexicon.hotplug.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * 启动时强制软下线的 locale 列表（逗号分隔，如 "zh-CN,de-DE"）。
     *
     * <p>R8-Backend-3 注：使用 {@code Optional<String>} 而非带 defaultValue 的 String，
     * 因为 SmallRyeConfig 在某些测试 profile 下会把 {@code defaultValue=""} 视为
     * "缺失"并报 SRCFG00014。{@code Optional} 不参与 required-validation。
     */
    @ConfigProperty(name = "aster.lexicon.startup-disabled-locales")
    Optional<String> startupDisabledLocales;

    /**
     * R5-Backend-5：strict drift 模式。
     * <ul>
     *   <li>true  → 替换若发生 ID 漂移（旧 jar 提供 zh-CN+zh-HK，新只提供 zh-CN）→ 拒绝替换，旧 jar 保持加载</li>
     *   <li>false（默认）→ 仅 warn，仍接受替换</li>
     * </ul>
     */
    @ConfigProperty(name = "aster.lexicon.replace.strict", defaultValue = "false")
    boolean strictReplace;

    /** 文件名 → 内容指纹（已加载的 jar）。 */
    private final ConcurrentHashMap<String, LoadedJar> loadedJars = new ConcurrentHashMap<>();

    /**
     * R11-Backend-Critical + R12-Minor-4: bounded striped lock pool。
     *
     * <p>Admin upload / delete 的事务跨多个 FS 操作（backup-copy / atomic-move / loadJarSync /
     * restore / delete-backup），它们都作用在同一个 canonical {@code target} 路径上。
     * 单独的 {@code synchronized loadJar} 只覆盖 loader 注册阶段，不够 ——
     * 两个同 fileName 的并发 upload 可在 backup-copy 与 move 之间交错，导致：
     * <ul>
     *   <li>A 把字节移到 target → B 覆盖 target → A loadJar 加载 B 的字节但响应 A 的 sha</li>
     *   <li>A 失败用 backup 恢复 → 覆盖 B 已成功 commit 的 jar</li>
     *   <li>双方 hadCanonical=false → A 失败 deleteQuietly(target) 删掉 B 的 jar</li>
     * </ul>
     * <p>R12-Minor-4：固定 64 把锁的 striped 池（而非 1-fileName-1-lock 的无界 map），
     * 按 {@code Math.floorMod(fileName.hashCode(), N_STRIPES)} 分配。内存恒定；
     * 偶发 hash 碰撞造成无关 fileName 的串行化在 admin 端点（低吞吐）可接受。
     */
    private static final int N_STRIPES = 64;
    private final java.util.concurrent.locks.ReentrantLock[] uploadLockStripes =
        createStripes();

    private static java.util.concurrent.locks.ReentrantLock[] createStripes() {
        java.util.concurrent.locks.ReentrantLock[] arr =
            new java.util.concurrent.locks.ReentrantLock[N_STRIPES];
        for (int i = 0; i < N_STRIPES; i++) {
            arr[i] = new java.util.concurrent.locks.ReentrantLock();
        }
        return arr;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watcherThread;
    private WatchService watchService;

    /**
     * 已加载 jar 的元数据。
     * @param sha256 jar 文件内容的 SHA-256（hex）
     * @param introducedIds discoverPlugins 后新增的 lexicon ID 集合
     * @param loader 该 jar 对应的 URLClassLoader（unload 时 close）
     */
    private record LoadedJar(String sha256, Set<String> introducedIds, URLClassLoader loader) {}

    /**
     * R8-Backend-3: 同步加载报告。
     *
     * <p>WatchService 路径走 fire-and-forget；运维端 upload 端点用这个能拿到精确结果，
     * 在 HTTP 响应里告诉操作员到底发生了什么（区分 ghost-jar、strict-drift、ok 等）。
     *
     * @param outcome 状态码（machine-readable）
     * @param message 人类可读说明
     * @param introduced 注册成功的 lexicon ID（如失败则为空）
     */
    public record LoadResult(String outcome, String message, Set<String> introduced) {
        public boolean ok() { return "ok".equals(outcome) || "unchanged".equals(outcome); }

        /**
         * R9-Backend-M5: 区分 client-error（永久拒绝，4xx）vs server-error（瞬时，5xx）
         * vs critical-half-state（5xx + 人工介入，500）。
         */
        public int httpStatus() {
            return switch (outcome) {
                case "ok", "unchanged" -> 200;
                // 4xx —— 客户端责任：jar 内容 / 元数据问题（重试无意义，需要客户端改 jar）
                // R10-Backend-M5: not_regular_file 与 preview_error 同样是客户端问题
                //   - not_regular_file: 上传了目录或 device file，重试一样
                //   - preview_error: providedLexiconIds() 抛错 → plugin 元数据 bug，重试一样
                case "strict_drift_rejected", "no_plugin_metadata",
                     "empty-introduced", "strict-drift", "invalid_jar",
                     "not_regular_file", "preview_error" -> 422;
                // 5xx —— 服务端瞬时故障，客户端可重试（IO / class linkage）
                case "io_error", "classloader_error", "discover-failed" -> 503;
                // 5xx —— 状态异常，需要人工介入
                case "rollback_failed",
                     "backup_restore_load_failed",
                     "backup_restore_io_failed" -> 500;
                default -> 500;
            };
        }
    }

    void onStart(@Observes StartupEvent ev) {
        applyStartupDisabledList();

        if (!enabled) {
            LOG.info("Hot-plug lexicon loader disabled (aster.lexicon.hotplug.enabled=false)");
            return;
        }
        Path dir = Path.of(hotplugDir);
        // R10-Backend-C1: 清理上一次进程崩溃留下的 staging / backup 文件。
        // 命名约定：{fileName}.{uuid}.staging / {fileName}.{uuid}.backup
        // 这些是事务中间产物，重启后无意义，留着只占磁盘 + 让 admin 端 grep 时困惑。
        cleanupPendingDir(dir.resolve("pending"));
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir);
                LOG.infof("Created hot-plug lexicon directory: %s", dir);
            } catch (IOException e) {
                LOG.warnf("Hot-plug dir %s unavailable: %s — hot-plug disabled", dir, e.getMessage());
                return;
            }
        }

        initialScan(dir);

        try {
            startWatcher(dir);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to start WatchService for %s; runtime hot-plug disabled", dir);
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        running.set(false);
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        // M7：关闭所有热插 classloader（应用退出，JVM 即将下班）
        for (Map.Entry<String, LoadedJar> e : loadedJars.entrySet()) {
            try {
                e.getValue().loader().close();
            } catch (IOException ex) {
                LOG.debugf("Closing hot-plug loader %s during shutdown: %s",
                    e.getKey(), ex.getMessage());
            }
        }
        loadedJars.clear();
    }

    /**
     * R10-Backend-C1 + R11-Backend-Major + R12-Backend-Major-3：
     * 清理 pending/ 目录中遗留的事务中间文件。
     *
     * <p>2-phase commit 期间若 JVM 崩溃，可能留下 {@code *.staging} / {@code *.backup} 文件。
     * 它们不对应任何活动事务、不应被 watcher 当 jar 加载（pending/ 子目录天然不在 watcher
     * 范围内）。启动时清理一次保持磁盘整洁，且避免后续 admin 调试 grep 时混淆。
     *
     * <p>R12-Backend-Major-3：mtime guard 30 分钟。R11 的 5 分钟在 GC pause / 慢盘 /
     * 大 jar (50 MiB) + backup-restore 慢 path 下可能误删活跃事务的 backup。30 分钟远
     * 超过合理上传 + 处理时间，但仍能在容器重启后清掉 stale。
     * <p>同时跳过 {@code *.cross-lock} 文件（R12-Major-2 引入），它们由 FileChannel
     * 持有，删了会破坏跨 replica 协调。
     * <p>Aster 建议 hotplug dir 用 ReadWriteOnce / single-writer，但 mtime + 类型 filter
     * 是廉价的防御层。
     */
    private static final long PENDING_STALE_MS = 30L * 60_000L;

    private static void cleanupPendingDir(Path pendingDir) {
        if (!Files.isDirectory(pendingDir)) return;
        long staleBefore = System.currentTimeMillis() - PENDING_STALE_MS;
        try (Stream<Path> stream = Files.list(pendingDir)) {
            stream
                .filter(p -> {
                    String n = p.getFileName().toString();
                    // R12-Major-3: 只清 staging/backup；cross-lock 由活跃进程持有，不能删
                    return n.endsWith(".staging") || n.endsWith(".backup");
                })
                .forEach(p -> {
                    try {
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        if (mtime > staleBefore) {
                            LOG.debugf("Skipping recent pending artifact (age=%dms): %s",
                                System.currentTimeMillis() - mtime, p.getFileName());
                            return;
                        }
                        Files.deleteIfExists(p);
                        LOG.infof("Cleaned stale pending artifact: %s", p.getFileName());
                    } catch (IOException ignored) {
                        // 单文件清理失败不阻塞启动 —— 下次重启再试
                    }
                });
        } catch (IOException e) {
            LOG.debugf("cleanupPendingDir(%s) failed: %s", pendingDir, e.getMessage());
        }
    }

    private void applyStartupDisabledList() {
        String raw = startupDisabledLocales.orElse("");
        if (raw.isBlank()) return;
        LexiconRegistry registry = LexiconRegistry.getInstance();
        for (String r : raw.split(",")) {
            String id = r.trim();
            if (id.isEmpty()) continue;
            if (registry.markUnavailable(id)) {
                LOG.infof("Startup disabled lexicon: %s", id);
            }
        }
    }

    private void initialScan(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                .forEach(p -> loadJar(p));  // discard LoadResult — initial scan logs internally
        } catch (IOException e) {
            LOG.warnf(e, "Initial scan of %s failed", dir);
        }
    }

    private void startWatcher(Path dir) throws IOException {
        watchService = dir.getFileSystem().newWatchService();
        dir.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY);
        running.set(true);
        watcherThread = new Thread(() -> watchLoop(dir), "lexicon-hotplug-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        LOG.infof("Hot-plug lexicon watcher started on %s", dir);
    }

    private void watchLoop(Path dir) {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                Path name = (Path) event.context();
                if (name == null) continue;
                String fileName = name.toString();
                // M6：只识别 *.jar 的最终态；中间 .part 文件被忽略
                if (!fileName.endsWith(".jar")) continue;

                Path full = dir.resolve(name);
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.ENTRY_CREATE
                        || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    loadJar(full);
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    unloadJar(fileName);
                }
            }
            if (!key.reset()) {
                LOG.warn("Watch key invalid; watcher exiting");
                return;
            }
        }
    }

    /**
     * 加载 / 替换一个 jar（事务式）。
     *
     * <p>幂等：相同文件名+相同 SHA256 直接 skip；相同文件名+不同 SHA256 触发 replace（M5）。
     *
     * <p>关键不变式：
     * <ol>
     *   <li><b>Preview before mutate</b> —— 创建新 loader 后先 {@code previewPluginIds}
     *       看它**声称**提供什么。零副作用</li>
     *   <li><b>Strict drift check</b> —— 替换场景下若 strict 模式且 newIds ≠ oldIds
     *       → 拒绝、close 新 loader、保留旧 lexicon 在线</li>
     *   <li><b>R8-Backend-1 Atomic swap</b> —— 所有写操作（unregisterByOwner +
     *       discoverPluginsDetailed + 失败回滚）包在 {@code registry.runAtomic} 事务里，
     *       SSE 监听器只看到事务出口的最终态；rejected 替换不会让监听器看到
     *       "消失 → 重现" 的中间抖动</li>
     *   <li><b>Transformer ownership</b> —— transformer 通过 owner CL 注册，
     *       unregisterByOwner 把旧 jar 的 transformer 一并清掉</li>
     * </ol>
     */
    private synchronized LoadResult loadJar(Path jar) {
        String fileName = jar.getFileName().toString();
        if (!Files.isRegularFile(jar)) {
            LOG.warnf("Not a regular file: %s — skipping", jar);
            return new LoadResult("not_regular_file",
                "path is not a regular file", Set.of());
        }

        String sha256;
        try {
            sha256 = sha256OfFile(jar);
        } catch (IOException e) {
            LOG.warnf(e, "Cannot hash %s; skipping", jar);
            return new LoadResult("io_error",
                "failed to hash jar: " + e.getMessage(), Set.of());
        }

        LoadedJar existing = loadedJars.get(fileName);
        boolean isReplace = false;
        if (existing != null) {
            if (existing.sha256().equals(sha256)) {
                LOG.debugf("Jar %s unchanged (sha256=%s) — skipping", fileName, sha256);
                return new LoadResult("unchanged",
                    "jar identical to currently loaded version",
                    existing.introducedIds());
            }
            LOG.infof("Jar %s content changed (old=%s, new=%s) — preparing replacement",
                fileName, existing.sha256(), sha256);
            isReplace = true;
        }

        if (!isValidJarShallow(jar)) {
            LOG.warnf("Jar %s failed shallow validation; skipping", fileName);
            return new LoadResult("invalid_jar",
                "shallow ZIP validation failed", Set.of());
        }

        URLClassLoader newLoader;
        try {
            URL url = jar.toUri().toURL();
            ClassLoader parent = LexiconRegistry.class.getClassLoader();
            newLoader = new URLClassLoader(
                "aster-lang-hotplug-" + fileName,
                new URL[]{ url },
                parent);
        } catch (Exception e) {
            LOG.warnf(e, "Cannot create loader for %s", fileName);
            return new LoadResult("classloader_error",
                "failed to construct URLClassLoader: " + e.getMessage(), Set.of());
        }

        LexiconRegistry registry = LexiconRegistry.getInstance();
        Set<String> oldIds = isReplace ? existing.introducedIds() : Set.of();

        // R5-Backend-2: dry-run 预览新 jar 提供什么
        Set<String> previewIds;
        try {
            previewIds = registry.previewPluginIds(newLoader);
        } catch (Throwable t) {
            LOG.warnf(t, "Preview for %s threw; closing new loader", fileName);
            cleanupFailedLoader(newLoader);
            return new LoadResult("preview_error",
                "previewPluginIds threw: " + t.getClass().getSimpleName(), Set.of());
        }

        if (previewIds.isEmpty()) {
            LOG.warnf("Jar %s plugin preview returned no lexicons — not loading, closing loader",
                fileName);
            cleanupFailedLoader(newLoader);
            return new LoadResult("no_plugin_metadata",
                "plugin providedLexiconIds() returned empty — either no LexiconPlugin "
                + "SPI entries or plugin did not override providedLexiconIds()",
                Set.of());
        }

        // R6-M2: strict drift check —— 严格模式 reject **任何** 差异（lost 或 added）
        if (isReplace && !oldIds.equals(previewIds)) {
            Set<String> lost = new HashSet<>(oldIds);
            lost.removeAll(previewIds);
            Set<String> added = new HashSet<>(previewIds);
            added.removeAll(oldIds);
            if (strictReplace) {
                LOG.errorf("REJECT replacement of %s (strict=true): lost=%s added=%s. "
                    + "Old jar remains active. Set aster.lexicon.replace.strict=false to allow drift.",
                    fileName, lost, added);
                cleanupFailedLoader(newLoader);
                return new LoadResult("strict_drift_rejected",
                    "strict replacement rejected: lost=" + lost + " added=" + added,
                    oldIds);
            }
            LOG.warnf("Replacement of %s changes lexicon set: lost=%s added=%s "
                + "(strict mode disabled — proceeding)",
                fileName, lost, added);
        }

        // R8-Backend-1: 整个 swap（unregister 旧 + discover 新 + drift 校验 + 失败回滚）
        // 包到 runAtomic 里，对 SSE 监听器表现为原子事务 —— 只在事务出口广播最终态。
        // R9-Backend-C2：回滚结果必须如实回报给 caller —— 不再把 rollback failure
        // 静默吞下而对外说 "rolled back"。
        final boolean replace = isReplace;
        final LoadedJar prev = existing;
        final Set<String> oldIdsFinal = oldIds;
        final java.util.concurrent.atomic.AtomicReference<Set<String>> introducedRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> outcomeRef =
            new java.util.concurrent.atomic.AtomicReference<>("ok");
        // R9-Backend-C2: 跟踪回滚是否真正成功
        final java.util.concurrent.atomic.AtomicBoolean rollbackOk =
            new java.util.concurrent.atomic.AtomicBoolean(true);

        registry.runAtomic(() -> {
            if (replace) {
                registry.unregisterByOwner(prev.loader());
                aster.core.canonicalizer.TransformerRegistry.unregisterByOwner(prev.loader());
            }
            Set<String> discovered;
            try {
                discovered = registry.discoverPluginsDetailed(newLoader);
            } catch (Throwable t) {
                LOG.warnf(t, "discoverPlugins for %s threw — rolling back to old loader inside tx",
                    jar.getFileName());
                rollbackInTx(newLoader, prev, replace, oldIdsFinal, rollbackOk);
                outcomeRef.set("discover-failed");
                return;
            }
            if (discovered.isEmpty()) {
                rollbackInTx(newLoader, prev, replace, oldIdsFinal, rollbackOk);
                outcomeRef.set("empty-introduced");
                return;
            }
            // R7-Backend-2 post-discovery strict drift re-check（事务内）
            if (replace && strictReplace && !oldIdsFinal.equals(discovered)) {
                Set<String> actualLost = new HashSet<>(oldIdsFinal);
                actualLost.removeAll(discovered);
                Set<String> actualAdded = new HashSet<>(discovered);
                actualAdded.removeAll(oldIdsFinal);
                LOG.errorf("REJECT replacement of %s post-discovery (strict=true): "
                    + "actual lost=%s actual added=%s. Rolling back inside tx.",
                    jar.getFileName(), actualLost, actualAdded);
                rollbackInTx(newLoader, prev, replace, oldIdsFinal, rollbackOk);
                outcomeRef.set("strict-drift");
                return;
            }
            introducedRef.set(discovered);
        });

        String outcome = outcomeRef.get();
        if (!"ok".equals(outcome)) {
            closeQuietly(newLoader);
            // R9-Backend-C2 + R10-Backend-M4: 如果原因是失败 + 回滚失败 → outcome 升级为
            // rollback_failed。把 "现在还活着哪些" + "失踪了哪些" 全告诉 operator，
            // 而不是只说"已半瘫"。
            if (replace && !rollbackOk.get()) {
                Set<String> actualNow = registry.availableIds();
                Set<String> missing = new HashSet<>(oldIdsFinal);
                missing.removeAll(actualNow);
                LOG.errorf("ROLLBACK FAILED for %s (after outcome=%s). "
                    + "Registry now exposes %s (originally %s). Missing locales: %s "
                    + "— manual recovery required.",
                    fileName, outcome, actualNow, oldIdsFinal, missing);
                return new LoadResult("rollback_failed",
                    "load was rejected (" + outcome + ") AND rollback to old jar partially failed; "
                    + "currently-live: " + actualNow + "; missing: " + missing
                    + "; manual recovery required",
                    actualNow);
            }
            String message = switch (outcome) {
                case "discover-failed" -> "discoverPluginsDetailed threw inside transaction; rolled back";
                case "empty-introduced" -> "no lexicon got registered (ghost jar?); rolled back";
                case "strict-drift" ->
                    "post-discovery strict drift check failed — actual registered IDs "
                    + "differ from previous jar; rolled back";
                default -> "load failed: " + outcome;
            };
            return new LoadResult(outcome, message, isReplace ? oldIds : Set.of());
        }

        Set<String> introduced = introducedRef.get();
        // 替换成功 → 关旧 loader、记录新 entry
        if (isReplace) {
            closeQuietly(existing.loader());
            loadedJars.remove(fileName);
        }
        loadedJars.put(fileName, new LoadedJar(sha256, Set.copyOf(introduced), newLoader));
        LOG.infof("Hot-plugged %s (sha256=%s, lexicons=%s)", fileName, sha256, introduced);
        return new LoadResult("ok",
            (isReplace ? "replaced" : "loaded") + " jar; registered " + introduced,
            introduced);
    }

    /**
     * R9-Backend-C2: 事务内回滚 —— 清新 loader + 重发旧 loader + 严格校验旧 ID 全部回来。
     *
     * <p>关键不变式：只有 {@code unregisterByOwner(new) + discoverPluginsDetailed(prev)} 都成功
     * **且**结果等于 oldIdsFinal，才视为 rollback 成功；否则 rollbackOk 设 false，
     * 调用方据此判定是否升级 outcome 为 {@code rollback_failed}。
     */
    private static void rollbackInTx(URLClassLoader newLoader,
                                     LoadedJar prev,
                                     boolean isReplace,
                                     Set<String> oldIdsFinal,
                                     java.util.concurrent.atomic.AtomicBoolean rollbackOk) {
        LexiconRegistry registry = LexiconRegistry.getInstance();
        try {
            registry.unregisterByOwner(newLoader);
            aster.core.canonicalizer.TransformerRegistry.unregisterByOwner(newLoader);
        } catch (Throwable cleanup) {
            LOG.errorf(cleanup, "In-tx cleanup of new loader threw");
            rollbackOk.set(false);
            // 即使 cleanup 抛，仍尝试重发 prev —— 把"看得见的部分"最大化
        }
        if (!isReplace) {
            return;  // 初次加载，没有 prev 可回滚
        }
        try {
            Set<String> reReg = registry.discoverPluginsDetailed(prev.loader());
            if (!reReg.containsAll(oldIdsFinal)) {
                LOG.errorf("In-tx rollback registered %s but expected %s", reReg, oldIdsFinal);
                rollbackOk.set(false);
            }
        } catch (Throwable rb) {
            LOG.errorf(rb, "In-tx rollback discoverPluginsDetailed(prev) threw");
            rollbackOk.set(false);
        }
    }

    /**
     * R12-Backend-Major-2: FS 级跨 replica 协调锁。
     *
     * <p>{@link #acquireUploadLock} 是 JVM-local，单 pod 内串行化生效。在 multi-replica +
     * 共享 hotplug dir (ReadWriteMany PVC) 部署下，两个 pod 仍能并发写同一 fileName。
     * 这里用 {@link java.nio.channels.FileChannel#tryLock} 取 OS 级 fcntl 锁：
     * <ul>
     *   <li>第二 pod 看到锁占用立刻返回 empty（非阻塞，避免 admin 请求挂死）</li>
     *   <li>锁文件 {@code pending/{fileName}.cross-lock} 与 JVM 进程绑定 —— 进程崩溃后
     *       OS 自动释放，下次启动 {@link #cleanupPendingDir} 不删 .cross-lock 文件
     *       （由 R12-Major-3 filter 保证）</li>
     *   <li>调用方拿到 AutoCloseable 后必须 try-with-resources 释放</li>
     * </ul>
     *
     * @param fileName sanitized 文件名
     * @return present = 锁取到；empty = 别的 replica 持有 → admin 应返回 409 Conflict
     */
    public Optional<CrossReplicaLock> tryAcquireCrossReplicaLock(String fileName) {
        Path pendingDir = Path.of(hotplugDir, "pending");
        Path lockPath = pendingDir.resolve(fileName + ".cross-lock");
        try {
            Files.createDirectories(pendingDir);
            java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                lockPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
            java.nio.channels.FileLock fl;
            try {
                fl = ch.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException
                     | java.nio.channels.ClosedChannelException ex) {
                // R13-Minor：仅同进程重复 tryLock / channel 已关 → 视为竞争
                try { ch.close(); } catch (IOException ignored) {}
                LOG.debugf("tryLock(%s) competition: %s", lockPath, ex.getClass().getSimpleName());
                return Optional.empty();
            } catch (IOException ioe) {
                try { ch.close(); } catch (IOException ignored) {}
                LOG.warnf(ioe, "tryLock(%s) IO failed", lockPath);
                return Optional.empty();
            }
            // OutOfMemoryError 等 Error 故意不 catch —— 它们应当 propagate
            if (fl == null) {
                // 另一 JVM 持有锁
                try { ch.close(); } catch (IOException ignored) {}
                return Optional.empty();
            }
            return Optional.of(new CrossReplicaLock(fl, ch));
        } catch (IOException e) {
            LOG.warnf(e, "tryAcquireCrossReplicaLock(%s) IO failed", fileName);
            return Optional.empty();
        }
    }

    /**
     * R12-Backend-Major-2 + R13-Critical: 跨 replica 文件锁 handle，try-with-resources 释放。
     *
     * <p><b>R13-Critical：close 不删 lockfile</b>。原因：split-brain race。
     * 假设 close 删 lockfile，则有：
     * <pre>
     *   pod A: open(foo.cross-lock) → tryLock OK → 工作中
     *   pod B: open(foo.cross-lock) → 持有同一 inode fd，等待 lock
     *   pod A: release + close + delete(foo.cross-lock)
     *   pod B: tryLock 现在 OK（OS 看到无人持有），锁的是已 unlink 但 inode 仍存活的文件
     *   pod C: open(foo.cross-lock) → fs 新建 inode（旧 inode 已 unlink） → tryLock OK
     *   B 和 C 各自锁着不同 inode，但都以为自己持有 "foo.cross-lock" 的逻辑锁 → 数据破坏
     * </pre>
     * 修复：保留 lockfile 让所有 acquirer 始终 open 同一 inode。
     * 残留几个空文件无伤大雅；{@link #cleanupPendingDir} 的 filter 已显式跳过 {@code .cross-lock}。
     */
    public static final class CrossReplicaLock implements AutoCloseable {
        private final java.nio.channels.FileLock fl;
        private final java.nio.channels.FileChannel ch;

        CrossReplicaLock(java.nio.channels.FileLock fl,
                         java.nio.channels.FileChannel ch) {
            this.fl = fl;
            this.ch = ch;
        }

        @Override
        public void close() {
            // R13-Critical：先 release lock，再 close channel；**不**删 lockfile
            try { fl.release(); } catch (IOException ignored) {}
            try { ch.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * R11-Backend-Critical + R12-Minor-4: 为给定 fileName 取得 striped 锁。
     *
     * <p>调用方约定：在整个 admin 事务（upload 的 backup-copy → atomic-move →
     * loadJarSync → 可能的 restore → delete-backup；或 DELETE 的 deleteIfExists）
     * 期间持有此锁；try/finally 释放。
     *
     * <p>R12-Minor-4：使用固定大小的 striped 池而非无界 map。两个 hash 碰撞的 fileName
     * 会共享锁、串行化（admin 端点低吞吐场景可接受），但内存恒定，无 DoS 风险。
     *
     * @param fileName sanitized 文件名（必须先过 sanitizeFileName 白名单）
     * @return 该 fileName 哈希到的 ReentrantLock；调用方负责 lock/unlock
     */
    public java.util.concurrent.locks.ReentrantLock acquireUploadLock(String fileName) {
        int idx = Math.floorMod(fileName.hashCode(), N_STRIPES);
        return uploadLockStripes[idx];
    }

    /**
     * R8-Backend-3：admin upload 同步加载入口。
     *
     * <p>WatchService 是 fire-and-forget；运维端 upload 端点应用此 API
     * 在 HTTP 响应中精确告知操作员到底发生了什么（ok / strict_drift_rejected /
     * no_plugin_metadata / discover-failed 等）。
     *
     * <p>调用 invariant：jar 已落盘到 {@link #hotplugDir} 下，filename 已 sanitize。
     * 调用此方法会触发同 {@link #loadJar(Path)} 完全一致的事务式加载流程。
     */
    public LoadResult loadJarSync(Path jar) {
        return loadJar(jar);
    }

    /**
     * R8-Backend-1: 早期失败（在 runAtomic 进入前）路径用的轻量清理。
     *
     * <p>调用时机：preview 阶段抛错 / preview 返回空 / strict 模式漂移拒绝。
     * 此时 new loader 还没注册过任何 lexicon/transformer，
     * 调 unregisterByOwner 是无害的防御性 no-op；真正要做的只有 close。
     */
    private static void cleanupFailedLoader(URLClassLoader loader) {
        LexiconRegistry.getInstance().unregisterByOwner(loader);
        aster.core.canonicalizer.TransformerRegistry.unregisterByOwner(loader);
        closeQuietly(loader);
    }

    /**
     * 物理 unregister 该 jar 引入的所有 lexicon，并 close 其 classloader（M7）。
     *
     * <p>R3：从 markUnavailable 升级为 unregister。原因：unregister 释放 id slot，
     * 允许同 id 的不同 jar 重新上传；markUnavailable 会让该 slot 一直被旧 Lexicon
     * 占用，新上传走 replace 路径而不是 fresh add。
     *
     * <p>仍有 in-flight 请求引用旧 Lexicon 实例的可能性（GC 自然回收），
     * 但 map lookup 已返回空 → 后续请求看到该语言"消失"。
     */
    private synchronized void unloadJar(String fileName) {
        LoadedJar entry = loadedJars.remove(fileName);
        if (entry == null) {
            LOG.debugf("Unload event for unknown jar %s; ignored", fileName);
            return;
        }
        LexiconRegistry registry = LexiconRegistry.getInstance();
        // R8-Backend-1: lexicon + transformer 一起放进 runAtomic 事务，
        // 监听器只看到一次"该 jar 全部下线"的最终态变更
        final java.util.concurrent.atomic.AtomicReference<Set<String>> removedRef =
            new java.util.concurrent.atomic.AtomicReference<>(Set.of());
        final java.util.concurrent.atomic.AtomicReference<Set<String>> removedTransformersRef =
            new java.util.concurrent.atomic.AtomicReference<>(Set.of());
        registry.runAtomic(() -> {
            removedRef.set(registry.unregisterByOwner(entry.loader()));
            removedTransformersRef.set(
                aster.core.canonicalizer.TransformerRegistry.unregisterByOwner(entry.loader()));
        });
        LOG.infof("Unregistered lexicons %s (jar %s removed)", removedRef.get(), fileName);
        if (!removedTransformersRef.get().isEmpty()) {
            LOG.infof("Unregistered transformers %s (jar %s)",
                removedTransformersRef.get(), fileName);
        }
        closeQuietly(entry.loader());
    }

    // ----------------------------------------------------------- helpers

    private static void closeQuietly(URLClassLoader loader) {
        try { loader.close(); } catch (IOException ignored) {}
    }

    /**
     * 最轻量的 jar 结构校验：必须能用 ZipInputStream 顺序读完，且至少含一个 entry。
     * 真正的 ZIP-bomb / META-INF/services 校验由 LexiconAdminResource 在落盘前做。
     */
    private static boolean isValidJarShallow(Path jar) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            int count = 0;
            while ((e = zis.getNextEntry()) != null) {
                count++;
                zis.closeEntry();
                if (count > 50_000) return false; // entry 数量上限，挡 zip-bomb
            }
            return count > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256OfFile(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}
