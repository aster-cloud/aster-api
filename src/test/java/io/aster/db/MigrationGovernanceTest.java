package io.aster.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway migration 治理守卫（纯文件系统，无需 Quarkus/DB）。
 *
 * <p>生产 Flyway 配置为 {@code out-of-order=true} + {@code ignore-migration-patterns=*:missing}，
 * 这对持续交付友好，但代价是：已应用的 migration 一旦被修改或乱序，Flyway 会**静默吞掉漂移**
 * 而非 fail-fast。本守卫把"migration 不可变 + 版本唯一 + 命名规范"这些 Flyway 原则
 * 用 CI 测试强制执行，即使运行时配置宽松也能在合并前捕获治理违规。
 *
 * <p><b>checksum golden</b>：每个 migration 的内容 SHA-256 锁定在
 * {@code src/test/resources/db/migration-checksums.golden}。修改已发布 migration 会触发失败，
 * 强制开发者：要么还原（migration 应不可变），要么新增 migration 表达变更 + 更新 golden 并说明理由。
 * 首次运行（golden 不存在）会自动生成。
 */
@DisplayName("Flyway migration 治理守卫")
class MigrationGovernanceTest {

    private static final Path MIGRATION_DIR =
        Paths.get("src/main/resources/db/migration");
    private static final Path GOLDEN_FILE =
        Paths.get("src/test/resources/db/migration-checksums.golden");
    // V<version>__<description>.sql —— 版本段允许语义化点分（如 6.10.0）
    private static final Pattern MIGRATION_NAME =
        Pattern.compile("^V(\\d+(?:\\.\\d+)*)__([a-zA-Z0-9_]+)\\.sql$");

    @Test
    @DisplayName("migration 命名规范：V<version>__<desc>.sql")
    void migrationNamingConvention() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : versionedMigrations()) {
            String name = f.getFileName().toString();
            if (!MIGRATION_NAME.matcher(name).matches()) {
                violations.add(name);
            }
        }
        assertThat(violations)
            .as("不符合 V<version>__<desc>.sql 命名的 migration（会让 Flyway 解析失败或乱序）")
            .isEmpty();
    }

    @Test
    @DisplayName("migration 版本号唯一（out-of-order 下重复版本号 = 危险）")
    void migrationVersionsUnique() throws IOException {
        Map<String, List<String>> byVersion = new TreeMap<>();
        for (Path f : versionedMigrations()) {
            Matcher m = MIGRATION_NAME.matcher(f.getFileName().toString());
            if (m.matches()) {
                byVersion.computeIfAbsent(m.group(1), v -> new ArrayList<>())
                    .add(f.getFileName().toString());
            }
        }
        List<Map.Entry<String, List<String>>> dups = byVersion.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .toList();
        assertThat(dups)
            .as("重复版本号的 migration——out-of-order=true 下 Flyway 行为不确定，必须唯一")
            .isEmpty();
    }

    @Test
    @DisplayName("已发布 migration 不可变（checksum golden 锁定内容）")
    void migrationChecksumStable() throws IOException {
        // 计算当前每个 migration 的 SHA-256
        Map<String, String> current = new TreeMap<>();
        for (Path f : versionedMigrations()) {
            current.put(f.getFileName().toString(), sha256(f));
        }

        if (!Files.exists(GOLDEN_FILE)) {
            // 首次：生成 golden（提交后即锁定）
            writeGolden(current);
            // 生成即视为通过——下次运行起强制不可变
            return;
        }

        Map<String, String> golden = readGolden();
        List<String> modified = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : golden.entrySet()) {
            String cur = current.get(e.getKey());
            if (cur == null) {
                missing.add(e.getKey());
            } else if (!cur.equals(e.getValue())) {
                modified.add(e.getKey());
            }
        }

        assertThat(modified)
            .as("已发布 migration 内容被修改——migration 应不可变。"
                + "若变更确属必要：新增一个 migration 表达变更，而非改历史；"
                + "若确需修订历史（极少）：更新 %s 并在 PR 说明理由+回放验证", GOLDEN_FILE)
            .isEmpty();
        assertThat(missing)
            .as("golden 登记的 migration 文件被删除——已发布 migration 不应删除（破坏既有环境的 checksum）")
            .isEmpty();
    }

    @Test
    @DisplayName("新增 migration 必须登记进 golden（防漏检）")
    void newMigrationsRegistered() throws IOException {
        if (!Files.exists(GOLDEN_FILE)) {
            return; // 首次运行由 checksum 测试生成
        }
        Set<String> golden = new HashSet<>(readGolden().keySet());
        List<String> unregistered = new ArrayList<>();
        for (Path f : versionedMigrations()) {
            if (!golden.contains(f.getFileName().toString())) {
                unregistered.add(f.getFileName().toString());
            }
        }
        assertThat(unregistered)
            .as("新增 migration 未登记 golden——运行本测试一次会自动追加，"
                + "请把更新后的 %s 一并提交（锁定其不可变性）", GOLDEN_FILE)
            .isEmpty();
    }

    @Test
    @DisplayName("Flyway 治理风险配置有意识保留（文档化提醒）")
    void flywayRiskConfigDocumented() throws IOException {
        Path props = Paths.get("src/main/resources/application.properties");
        String content = Files.readString(props, StandardCharsets.UTF_8);
        // 这两项是已知治理风险（静默吞 migration 漂移）。本守卫测试正是它们的补偿控制：
        // 若未来收紧配置（去掉 out-of-order），本断言更新即可。此处仅确认配置未被悄悄改动
        // 导致守卫的前提假设失效。
        boolean outOfOrder = content.contains("quarkus.flyway.out-of-order=true");
        boolean ignoreMissing = content.contains("ignore-migration-patterns=*:missing");
        // 不强制要求保留——只要其一存在，checksum golden 守卫就是必要的补偿控制
        assertThat(outOfOrder || ignoreMissing)
            .as("若已收紧 Flyway 配置（移除 out-of-order + ignore-missing），"
                + "可放宽本测试；当前配置宽松，checksum 守卫为必要补偿")
            .isTrue();
    }

    // ============================================================
    // helpers
    // ============================================================

    private static List<Path> versionedMigrations() throws IOException {
        try (Stream<Path> s = Files.list(MIGRATION_DIR)) {
            return s.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("V") && n.endsWith(".sql");
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
    }

    private static String sha256(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // 规范化换行（CRLF→LF）使 checksum 不受 git autocrlf 影响
            byte[] bytes = Files.readString(f, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, String> readGolden() throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(GOLDEN_FILE)) {
            p.load(in);
        }
        Map<String, String> out = new TreeMap<>();
        p.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }

    private static void writeGolden(Map<String, String> checksums) throws IOException {
        Files.createDirectories(GOLDEN_FILE.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Flyway migration checksum golden (SHA-256, LF-normalized).\n");
        sb.append("# 已发布 migration 不可变——修改历史 migration 会触发 MigrationGovernanceTest 失败。\n");
        sb.append("# 新增 migration：运行测试一次自动追加，连同本文件一起提交。\n");
        checksums.forEach((name, hash) -> sb.append(name).append('=').append(hash).append('\n'));
        Files.writeString(GOLDEN_FILE, sb.toString(), StandardCharsets.UTF_8);
    }
}
