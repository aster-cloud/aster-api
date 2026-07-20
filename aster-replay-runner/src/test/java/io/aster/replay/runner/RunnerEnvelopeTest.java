package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunnerEnvelopeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void errorEnvelopeHasNoReplayMetadata() throws Exception {
        RunnerEnvelope env = RunnerEnvelope.error("PARSE", "boom", "parse");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(env));
        assertEquals("ERROR", node.get("outcome").asText());
        assertEquals("PARSE", node.get("errorCode").asText());
        assertEquals("parse", node.get("phase").asText());
        // ★错误 envelope 绝不含 replayMetadata（成功与失败不共 schema）
        assertFalse(node.has("replayMetadata"));
    }

    @Test
    void successEnvelopeOutcomeIsSuccess() throws Exception {
        // ★用 ReplayMetadata 真实 11 字段 canonical 构造（ReplayMetadata.java:58-74 实证），
        //   不手编不存在的字段/工厂。M2 后 3 字段（canonicalInput/Output/Trace）传 null。
        io.aster.policy.replay.ReplayMetadata rm = new io.aster.policy.replay.ReplayMetadata(
            "abi=V1;core=dev;validator=dev;build=test",   // runtimeToolchainId
            io.aster.policy.replay.ReplayMetadata.CANONICALIZATION_VERSION,
            "inHash", "outHash", "traceHash",             // canonicalInput/Output/traceHash
            java.util.List.of(),                          // reasonCodes
            "REPLAYABLE",                                 // replayabilityStatus
            java.util.List.of(),                          // replayabilityReasons
            null, null, null);                            // M2: canonicalInput/Output/Trace
        RunnerEnvelope env = RunnerEnvelope.success(rm);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(env));
        assertEquals("SUCCESS", node.get("outcome").asText());
        assertTrue(node.has("replayMetadata"));
        assertFalse(node.has("errorCode"));
    }
}
