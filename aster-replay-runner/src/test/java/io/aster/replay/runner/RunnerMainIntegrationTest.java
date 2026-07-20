package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * main() 全链集成：喂 stdin JSON，捕获 stdout envelope（最后一行完整 JSON）。
 * 用抽出的 run(InputStream,PrintStream) 入口避免真调 System.exit。
 *
 * <p>两个用例：
 * <ul>
 *   <li>import-free policy → SUCCESS envelope（exit 0、traceHash 非 null，与生产
 *       replayCapture 路径对齐防 parity 分叉）。</li>
 *   <li>跨模块 import policy（真实语法 {@code Use ... version ... as ...}）→
 *       StandaloneReplayExecutor 的 (null,true) 触发 ModuleResolutionException →
 *       被 DynamicCnlExecutor 包装为 ModuleExecutionException → runner 映射 MODULE
 *       错误 envelope（exit≠0、不含 replayMetadata）。</li>
 * </ul>
 */
class RunnerMainIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successProducesReplayMetadataEnvelope() throws Exception {
        // ★CNL 语法用真实语料形态；locale "en-US"；trace/effectiveReplayCapture 在 toCoreRequest 内固定 true。
        String reqJson = """
            {"tenantId":"tenant-1","source":"Module probe.\\nRule main given x as Int, produce Int:\\n  Return x.",
             "input":{"x":1},"locale":"en-US","functionName":"main","aliasSet":null}
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RunnerMain.run(
            new ByteArrayInputStream(reqJson.getBytes()), new PrintStream(out));
        assertEquals(0, code);
        String lastLine = out.toString().strip().lines().reduce((a, b) -> b).orElseThrow();
        JsonNode env = mapper.readTree(lastLine);
        assertEquals("SUCCESS", env.get("outcome").asText());
        assertTrue(env.get("replayMetadata").has("canonicalInputHash"));
        // ★trace=true 路径 → traceHash 非 null（与生产 replayCapture 对齐，防 parity 分叉）。
        assertFalse(env.get("replayMetadata").get("traceHash").isNull());
        // ★根因回归守门（Task 0 校准实证）：仅 traceHash 非 null 不够——若进程级 trace PE gate
        // （TraceAccess.setEnabled）未开，armCurrentThread 后引擎仍不采集步骤，canonicalTrace 会
        // 退化为 steps=[]，此时 traceHash 仍非 null 但与 aster-api（步骤非空）逐字节分叉。这里
        // 直接断言 canonicalTrace 含非空 steps，锁死「有 hash 但未采集有效步骤」的分叉根因。
        JsonNode canonicalTrace = mapper.readTree(
            env.get("replayMetadata").get("canonicalTrace").asText());
        assertTrue(canonicalTrace.get("steps").isArray() && !canonicalTrace.get("steps").isEmpty(),
            "canonicalTrace.steps 必须非空——空 steps=trace PE gate 未开，与生产 replayCapture 路径分叉");
    }

    @Test
    void importPolicyFailsClosed() throws Exception {
        // 跨模块 import → StandaloneReplayExecutor 的 (null,true) → ModuleResolutionException →
        // ModuleExecutionException（包装）→ MODULE 错误 envelope，exit≠0，不含 replayMetadata。
        // ★真实跨模块 import 语法：Use <module.path> version <N> as <Alias>.（非 Import other.）。
        String reqJson = """
            {"tenantId":"tenant-1","source":"Module probe.\\nUse risk.Scoring version 1 as Score.\\nRule main given x as Int, produce Int:\\n  Return x.",
             "input":{"x":1},"locale":"en-US","functionName":"main","aliasSet":null}
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RunnerMain.run(
            new ByteArrayInputStream(reqJson.getBytes()), new PrintStream(out));
        assertNotEquals(0, code);
        String lastLine = out.toString().strip().lines().reduce((a, b) -> b).orElseThrow();
        JsonNode env = mapper.readTree(lastLine);
        assertEquals("ERROR", env.get("outcome").asText());
        assertEquals("MODULE", env.get("errorCode").asText());
        assertFalse(env.has("replayMetadata"));
    }
}
