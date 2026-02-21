package io.aster.policy.parser;

import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试自然语言 CNL 解析（不使用 Canonicalizer）
 *
 * 验证 ANTLR 解析器可以直接解析自然语言风格的 CNL 语法。
 */
class NaturalLanguageParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 辅助方法：直接解析 CNL 源代码（不使用 Canonicalizer）
     */
    private Module parseDirectly(String source) {
        CharStream charStream = CharStreams.fromString(source);
        AsterCustomLexer lexer = new AsterCustomLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        tokens.fill();
        tokens.seek(0);

        AsterParser parser = new AsterParser(tokens);
        parser.removeErrorListeners();

        AsterParser.ModuleContext moduleCtx = parser.module();

        if (moduleCtx == null) {
            fail("Parser returned null ModuleContext");
        }

        AstBuilder builder = new AstBuilder();
        Module module = builder.visitModule(moduleCtx);

        if (module == null) {
            fail("AstBuilder returned null Module");
        }

        return module;
    }

    @Test
    void testAutoInsuranceNaturalLanguageCnl() throws Exception {
        String source = """
            Module insurance.auto.

            a Driver has id, age, yearsLicensed, accidents, violations.

            a Vehicle has vin, year, value, safetyRating.

            a Quote has approved, premium, deductible, reason.

            Rule generateQuote given driver, vehicle:
              If driver.age less than 18:
                Return Quote with approved = false, premium = 0, deductible = 0, reason = "Driver under 18".
              If driver.accidents greater than 3:
                Return Quote with approved = false, premium = 0, deductible = 0, reason = "Too many accidents".
              Let basePremium be calculateBase with driver, vehicle.
              Let riskFactor be calculateRisk with driver.
              Let finalPremium be basePremium times riskFactor divided by 100.
              Return Quote with approved = true, premium = finalPremium, deductible = 500, reason = "Approved".

            Rule calculateBase given driver, vehicle:
              If driver.age less than 25:
                Return 300.
              If driver.age less than 65:
                Return 200.
              Return 250.

            Rule calculateRisk given driver:
              Let base be 100.
              If driver.accidents greater than 0:
                Let base be base plus driver.accidents times 20.
              If driver.violations greater than 0:
                Let base be base plus driver.violations times 10.
              Return base.
            """;

        // 1. 直接解析（不使用 Canonicalizer）
        Module module = parseDirectly(source);

        assertNotNull(module);
        assertEquals("insurance.auto", module.name());
        assertEquals(6, module.decls().size());

        // 2. 验证函数签名和结构
        Decl.Func generateQuote = (Decl.Func) module.decls().get(3);
        assertEquals("generateQuote", generateQuote.name());
        assertEquals(2, generateQuote.params().size());

        // 3. 降级到 Core IR
        CoreLowering lowering = new CoreLowering();
        CoreModel.Module coreModule = lowering.lowerModule(module);

        assertNotNull(coreModule);
        assertEquals("insurance.auto", coreModule.name);

        // 4. 序列化为 JSON（验证完整流程）
        String coreJson = MAPPER.writeValueAsString(coreModule);
        assertNotNull(coreJson);
        assertTrue(coreJson.contains("insurance.auto"));
        assertTrue(coreJson.contains("generateQuote"));

        System.out.println("=== 自然语言 CNL 直接解析成功（新语法）===");
        System.out.println("模块名: " + module.name());
        System.out.println("声明数: " + module.decls().size());
        System.out.println("Core JSON 长度: " + coreJson.length());
    }

    @Test
    void testNaturalLanguageOperators() {
        // 测试各种自然语言运算符（使用新语法）
        String source = """
            Module test.

            Rule testOperators given a, b:
              If a less than b:
                Return 1.
              If a greater than b:
                Return 2.
              Let sum be a plus b.
              Let diff be a minus b.
              Let product be a times b.
              Let quotient be a divided by b.
              Return sum.
            """;

        Module module = parseDirectly(source);
        assertNotNull(module);

        Decl.Func func = (Decl.Func) module.decls().get(0);
        assertEquals("testOperators", func.name());
        assertEquals(2, func.params().size());

        // 验证可以降级
        CoreLowering lowering = new CoreLowering();
        CoreModel.Module coreModule = lowering.lowerModule(module);
        assertNotNull(coreModule);
    }

    @Test
    void testWithCallSyntax() {
        String source = """
            Module test.

            Rule helper given x:
              Return x times 2.

            Rule main:
              Let result be helper with 10.
              Return result.
            """;

        Module module = parseDirectly(source);
        assertNotNull(module);
        assertEquals(2, module.decls().size());

        // 验证可以降级
        CoreLowering lowering = new CoreLowering();
        CoreModel.Module coreModule = lowering.lowerModule(module);
        assertNotNull(coreModule);
    }
}
