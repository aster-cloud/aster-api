package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RunnerRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesSchema2Json() throws Exception {
        String json = """
            {"tenantId":"t1","source":"Module m.\\nRule r given x: Return x.",
             "input":{"x":1},"locale":"en","functionName":"r",
             "aliasSet":{"RULE":["规则"]}}
            """;
        RunnerRequest req = mapper.readValue(json, RunnerRequest.class);
        assertEquals("t1", req.tenantId());
        assertEquals("en", req.locale());
        assertEquals("r", req.functionName());
        assertEquals(List.of("规则"), req.aliasSet().get("RULE"));
        assertNotNull(req.input());
    }

    @Test
    void nullAliasSetAllowed() throws Exception {
        String json = """
            {"tenantId":"t1","source":"x","input":{},"locale":"en",
             "functionName":"r","aliasSet":null}
            """;
        RunnerRequest req = mapper.readValue(json, RunnerRequest.class);
        assertNull(req.aliasSet());
    }
}
