package io.aster.replay.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.replay.runner.RunnerMain;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * D-1 真 distribution-parity 门（每 PR，host JVM，无 arm64/QEMU）。
 *
 * <p>对每个 import-free corpus fixture 同一 JVM 内驱两侧：
 * <ul>
 *   <li>A（权威）= aster-api 生产 {@code evaluateSource(replayCapture=true)} 的 ReplayMetadata
 *       （经 HMAC harness，复用 {@link GenExpectedCorpusTest#driveAuthority}）。</li>
 *   <li>B（runner distribution）= runner distribution 的 {@link RunnerMain#run(java.io.InputStream, PrintStream)}
 *       in-JVM 喂同一 req JSON，取 stdout 最后一行 envelope 的 ReplayMetadata。</li>
 * </ul>
 * per-field {@code assertEquals} 5 字段（排除 {@code runtimeToolchainId}——A/B 的 {@code build=} 段天然不同）。
 * 任一分叉 → 红。
 *
 * <p>★这是真门：{@link GenExpectedCorpusTest} 只驱权威侧 A 断言 REPLAYABLE，不跑 runner、不比对；
 * 本测试才比 A vs B。分叉即 runner 打包/locale/装配在同 JDK 下引入了差异（真 bug），或 harness 未同构驱动。
 */
@QuarkusTest
@TestProfile(GenExpectedCorpusTest.HmacProfile.class)   // 复用同一 HMAC profile（关全局签名 + 强制 HMAC 路径）
class RunnerDistributionParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 5 个 parity 字段——排除 runtimeToolchainId（A/B build= 段天然不同，比它必假红）。 */
    private static final List<String> PARITY_FIELDS = List.of(
        "canonicalInputHash", "canonicalOutputHash", "canonicalizationVersion",
        "replayabilityStatus", "traceHash");

    @Test
    void everyCorpusFixtureDistributionParity() throws Exception {
        Path corpusDir = GenExpectedCorpusTest.resolveCorpusDir();   // ★复用其 corpus 目录解析
        List<Path> reqFiles;
        try (var stream = Files.list(corpusDir)) {
            reqFiles = stream
                .filter(p -> p.getFileName().toString().endsWith(".req.json"))
                .sorted()
                .toList();
        }
        assertFalse(reqFiles.isEmpty(), "corpus 不应为空：" + corpusDir);

        for (Path req : reqFiles) {
            String name = req.getFileName().toString().replace(".req.json", "");
            String reqJson = Files.readString(req, StandardCharsets.UTF_8);

            // 权威侧 A：经 HMAC harness POST /evaluate-source?replayCapture=true（复用 GenExpectedCorpusTest 驱动）
            JsonNode authorityRm = GenExpectedCorpusTest.driveAuthority(reqJson);

            // runner distribution 侧 B：in-JVM 调 RunnerMain.run（喂同一 req JSON，取 stdout 最后一行 envelope）
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = RunnerMain.run(
                new ByteArrayInputStream(reqJson.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(0, code, name + ": runner 应成功（exit 0）");
            String lastLine = out.toString(StandardCharsets.UTF_8).strip().lines()
                .reduce((a, b) -> b).orElseThrow();
            JsonNode envelope = MAPPER.readTree(lastLine);
            assertEquals("SUCCESS", envelope.get("outcome").asText(), name + ": runner outcome");
            JsonNode runnerRm = envelope.get("replayMetadata");

            // per-field 比对 5 字段（值一致，非字面 envelope 字节）
            for (String f : PARITY_FIELDS) {
                assertEquals(authorityRm.get(f).asText(null), runnerRm.get(f).asText(null),
                    name + ": 字段 " + f + " 分叉（runner 打包/locale/装配在同 JDK 下引入了差异）");
            }
        }
    }
}
