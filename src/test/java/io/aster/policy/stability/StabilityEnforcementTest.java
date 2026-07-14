package io.aster.policy.stability;

import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import jakarta.ws.rs.WebApplicationException;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StabilityEnforcement 单元测试（ADR 0031 P0-C 服务端 enforcement）。
 *
 * <p>验证：warn-mode 产 W600 warning 不阻断；strict-mode（approve/activate）检出 W600 → 422；
 * cache 盲区（scanCoreJson 反序列化）能检出；regulated tenant 保存 strict；Stable 不触发。
 */
@DisplayName("StabilityEnforcement 服务端门禁")
class StabilityEnforcementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 含 Experimental 特性（@deprecated）的源码 → coreJson。
    private static final String EXPERIMENTAL_SRC = """
            Module test.exp.

            @deprecated
            Rule oldRule, produce Text:
              Return "x".
            """;

    // 纯 Stable 源码。
    private static final String STABLE_SRC = """
            Module test.stable.

            Rule greet given name as Text, produce Text. It performs io:
              Return name.
            """;

    private StabilityEnforcement newEnforcement(String regulatedCsv) {
        StabilityEnforcement e = new StabilityEnforcement();
        e.regulatedTenantsCsv = regulatedCsv;
        return e;
    }

    @Test
    @DisplayName("warn-mode：Experimental 产 W600 warning 但不 blocked")
    void warnModeProducesWarningNotBlocked() {
        StabilityEnforcement e = newEnforcement("");
        var result = e.scan(compileToCore(EXPERIMENTAL_SRC), false);
        assertFalse(result.blocked(), "warn-mode 不 blocked");
        assertTrue(result.diagnostics().stream().anyMatch(d -> "W600".equals(d.code())), "应有 W600");
        assertTrue(result.diagnostics().stream().allMatch(d -> "warning".equals(d.severity())), "severity=warning");
        assertTrue(result.diagnostics().stream().anyMatch(d -> "deprecated-annotation".equals(d.featureId())));
    }

    @Test
    @DisplayName("strict-mode：Experimental → blocked")
    void strictModeBlocks() {
        StabilityEnforcement e = newEnforcement("");
        var result = e.scan(compileToCore(EXPERIMENTAL_SRC), true);
        assertTrue(result.blocked(), "strict-mode 有 W600 应 blocked");
        // severity 恒 warning，blocking 标记 strict。
        assertTrue(result.diagnostics().stream().allMatch(d -> "warning".equals(d.severity())));
        assertTrue(result.diagnostics().stream().anyMatch(CompilationResult.Diagnostic::blocking));
    }

    @Test
    @DisplayName("Stable 源码不触发（warn 与 strict 都空/不 blocked）")
    void stableNotTriggered() {
        StabilityEnforcement e = newEnforcement("");
        var warn = e.scan(compileToCore(STABLE_SRC), false);
        var strict = e.scan(compileToCore(STABLE_SRC), true);
        assertTrue(warn.diagnostics().isEmpty());
        assertFalse(strict.blocked(), "Stable strict 也不 blocked");
    }

    @Test
    @DisplayName("★cache 盲区：scanCoreJson 反序列化 coreJson 后仍检出 W600（ADR §3.7）")
    void scanCoreJsonDetectsFromSerializedIr() throws Exception {
        StabilityEnforcement e = newEnforcement("");
        String coreJson = MAPPER.writeValueAsString(compileToCore(EXPERIMENTAL_SRC));
        var result = e.scanCoreJson(coreJson, true);
        assertTrue(result.blocked(), "从 coreJson 反序列化后仍应检出 Experimental 并 block");
    }

    @Test
    @DisplayName("enforceVersion：strict surface（activate）检出 Experimental → 抛 422")
    void enforceVersionRejects422() throws Exception {
        StabilityEnforcement e = newEnforcement("");
        String coreJson = MAPPER.writeValueAsString(compileToCore(EXPERIMENTAL_SRC));
        WebApplicationException ex = assertThrows(WebApplicationException.class, () ->
            e.enforceVersion(1L, "test.exp.oldRule", "tenant1", coreJson, null, "en-US", null,
                StabilityEnforcement.Surface.ACTIVATE, "actor"));
        assertEquals(422, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("enforceVersion：Stable 版本 activate 通过（不抛）")
    void enforceVersionAllowsStable() throws Exception {
        StabilityEnforcement e = newEnforcement("");
        String coreJson = MAPPER.writeValueAsString(compileToCore(STABLE_SRC));
        e.enforceVersion(1L, "test.stable.greet", "tenant1", coreJson, null, "en-US", null,
            StabilityEnforcement.Surface.ACTIVATE, "actor");
    }

    @Test
    @DisplayName("★coreJson=null 但 content Experimental → 从源码现编译检出并拒（Codex P0 漏报修复）")
    void enforceVersionCompilesFromContentWhenCoreJsonNull() {
        StabilityEnforcement e = newEnforcement("");
        // coreJson 空（createVersion 不填），但 content 用 Experimental → 不能放行。
        WebApplicationException ex = assertThrows(WebApplicationException.class, () ->
            e.enforceVersion(1L, "test.exp.oldRule", "tenant1", null, EXPERIMENTAL_SRC, "en-US", null,
                StabilityEnforcement.Surface.ACTIVATE, "actor"));
        assertEquals(422, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("★strict fail-closed：coreJson 与 content 都空 → 拒（不放行，Codex L3）")
    void enforceVersionFailClosedWhenNothingScannable() {
        StabilityEnforcement e = newEnforcement("");
        WebApplicationException ex = assertThrows(WebApplicationException.class, () ->
            e.enforceVersion(1L, "test.x", "tenant1", null, null, "en-US", null,
                StabilityEnforcement.Surface.ACTIVATE, "actor"));
        assertEquals(422, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("★strict fail-closed：损坏 coreJson → 拒（不当稳定放行）")
    void enforceVersionFailClosedOnMalformedCoreJson() {
        StabilityEnforcement e = newEnforcement("");
        WebApplicationException ex = assertThrows(WebApplicationException.class, () ->
            e.enforceVersion(1L, "test.x", "tenant1", "{not valid core json", null, "en-US", null,
                StabilityEnforcement.Surface.ACTIVATE, "actor"));
        assertEquals(422, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("★coreJson=null + content 用 aliasSet + Stable → activate 通过（Codex 复审：别名版本不误拒）")
    void enforceVersionAllowsStableWithAliasWhenCoreJsonNull() {
        StabilityEnforcement e = newEnforcement("");
        // TIMES → "multiplied by"（低风险运算符别名，白名单允许）。源码用别名写，coreJson=null，
        // 须**带 aliasSet 编译**才能解析——否则无别名重解释会失败被误 fail-closed 拒（Codex P1）。
        String aliasSrc = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 3.";
        String aliasSetJson = "{\"TIMES\":[\"multiplied by\"]}";
        // content 用别名 + Stable（无 Experimental）→ 应通过（不抛）。
        e.enforceVersion(1L, "M.p", "tenant1", null, aliasSrc, "en-US", aliasSetJson,
            StabilityEnforcement.Surface.ACTIVATE, "actor");
    }

    @Test
    @DisplayName("strictFor：approve/activate 恒 strict；save 仅 regulated tenant；playground warn")
    void strictForSurfaceDefaults() {
        StabilityEnforcement e = newEnforcement("regTenant");
        assertTrue(e.strictFor(StabilityEnforcement.Surface.APPROVE, "any"));
        assertTrue(e.strictFor(StabilityEnforcement.Surface.ACTIVATE, "any"));
        assertFalse(e.strictFor(StabilityEnforcement.Surface.COMPILE_PLAYGROUND, "any"));
        assertTrue(e.strictFor(StabilityEnforcement.Surface.SAVE, "regTenant"), "regulated tenant save=strict");
        assertFalse(e.strictFor(StabilityEnforcement.Surface.SAVE, "normalTenant"), "普通 tenant save=warn");
    }

    /** Java 编译管线：源码 → CoreModel（复用 GoldenTestRunner 范式）。 */
    private static CoreModel.Module compileToCore(String source) {
        String canonicalized = new Canonicalizer().canonicalize(source);
        var charStream = CharStreams.fromString(canonicalized);
        var lexer = new AsterCustomLexer(charStream);
        var tokens = new CommonTokenStream(lexer);
        var parser = new AsterParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int pos, String msg, RecognitionException e) {
                throw new IllegalStateException("解析错误 @" + line + ":" + pos + " " + msg);
            }
        });
        AsterParser.ModuleContext moduleCtx = parser.module();
        aster.core.ast.Module ast = new AstBuilder().visitModule(moduleCtx);
        return new CoreLowering().lowerModule(ast);
    }
}
