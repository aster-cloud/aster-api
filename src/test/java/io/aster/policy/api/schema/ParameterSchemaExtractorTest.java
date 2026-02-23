package io.aster.policy.api.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ParameterSchemaExtractor 单元测试
 *
 * 覆盖：英文/中文/德语基本类型、结构体、isPrimitiveType 判断
 */
class ParameterSchemaExtractorTest {

    // ─── isPrimitiveType 单元测试 ────────────────────────────────

    @Nested
    class IsPrimitiveTypeTest {

        @Test
        void canonicalNames_shouldBeRecognized() {
            // TypeInference.isPrimitiveType 规范名
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Int")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Float")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Bool")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Text")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("DateTime")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Long")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Double")).isTrue();
        }

        @Test
        void englishAliases_shouldBeRecognized() {
            assertThat(ParameterSchemaExtractor.isPrimitiveType("integer")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Integer")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("decimal")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("string")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("String")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("boolean")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("date")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("datetime")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("time")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("number")).isTrue();
        }

        @Test
        void customTypeNames_shouldNotBeRecognized() {
            assertThat(ParameterSchemaExtractor.isPrimitiveType("LoanApplication")).isFalse();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Driver")).isFalse();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("Address")).isFalse();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("")).isFalse();
            assertThat(ParameterSchemaExtractor.isPrimitiveType(null)).isFalse();
        }

        @Test
        void lowercaseCanonicalNames_shouldBeRecognized() {
            // TypeInference 使用 PascalCase，但 switch 应覆盖全小写变体
            assertThat(ParameterSchemaExtractor.isPrimitiveType("int")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("float")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("bool")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("text")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("long")).isTrue();
            assertThat(ParameterSchemaExtractor.isPrimitiveType("double")).isTrue();
        }
    }

    // ─── extractSchema：英文 CNL ───────────────────────────────

    @Nested
    class EnglishSchemaTest {

        @Test
        void primitiveParameters_shouldExtractCorrectly() {
            String source = """
                Module TestModule.
                Rule evaluate given score as Int and name as Text:
                  Return score.
                """;

            var result = ParameterSchemaExtractor.extractSchema(source, null, "en-US");

            assertThat(result.success()).isTrue();
            assertThat(result.moduleName()).isEqualTo("TestModule");
            assertThat(result.functionName()).isEqualTo("evaluate");
            assertThat(result.parameters()).hasSize(2);

            var scoreParam = result.parameters().get(0);
            assertThat(scoreParam.name()).isEqualTo("score");
            assertThat(scoreParam.type()).isEqualTo("Int");
            assertThat(scoreParam.typeKind()).isEqualTo(ParameterSchemaExtractor.TypeKind.PRIMITIVE);

            var nameParam = result.parameters().get(1);
            assertThat(nameParam.name()).isEqualTo("name");
            assertThat(nameParam.type()).isEqualTo("Text");
            assertThat(nameParam.typeKind()).isEqualTo(ParameterSchemaExtractor.TypeKind.PRIMITIVE);
        }

        @Test
        void structParameter_shouldExtractFields() {
            String source = """
                Module TestModule.
                Define Applicant has creditScore as Int, income as Float.
                Rule evaluate given applicant as Applicant:
                  Return applicant.creditScore.
                """;

            var result = ParameterSchemaExtractor.extractSchema(source, null, "en-US");

            assertThat(result.success()).isTrue();
            assertThat(result.parameters()).hasSize(1);

            var param = result.parameters().get(0);
            assertThat(param.name()).isEqualTo("applicant");
            assertThat(param.typeKind()).isEqualTo(ParameterSchemaExtractor.TypeKind.STRUCT);
            assertThat(param.fields()).hasSize(2);

            var creditField = param.fields().get(0);
            assertThat(creditField.name()).isEqualTo("creditScore");
            assertThat(creditField.typeKind()).isEqualTo(ParameterSchemaExtractor.TypeKind.PRIMITIVE);

            var incomeField = param.fields().get(1);
            assertThat(incomeField.name()).isEqualTo("income");
            assertThat(incomeField.typeKind()).isEqualTo(ParameterSchemaExtractor.TypeKind.PRIMITIVE);
        }
    }

    // ─── extractSchema：中文 CNL ───────────────────────────────

    @Nested
    class ChineseSchemaTest {

        @Test
        void chineseCnl_typeNamesShouldBeCanonicalEnglish() {
            // 中文语法：规则 funcName 给定 参数名 as 类型名：
            // Canonicalizer 将 "给定" → "given"、"整数" → "Int"
            // "as" 是固定 ANTLR token，不需翻译
            String source = """
                模块 测试模块。
                规则 评估 给定 分数 as 整数：
                  返回 分数。
                """;

            var result = ParameterSchemaExtractor.extractSchema(source, null, "zh-CN");

            assertThat(result.success()).isTrue();
            assertThat(result.parameters()).hasSize(1);

            var param = result.parameters().get(0);
            assertThat(param.name()).isEqualTo("分数");
            // Canonicalizer 将 "整数" 翻译为 "Int"，Core IR 中类型名为英文规范名
            assertThat(param.type()).isEqualTo("Int");
            assertThat(param.typeKind()).isEqualTo(ParameterSchemaExtractor.TypeKind.PRIMITIVE);
        }
    }

    // ─── extractSchema：德语 CNL ───────────────────────────────

    @Nested
    class GermanSchemaTest {

        @Test
        void germanCnl_typeNamesShouldBeCanonicalEnglish() {
            // 德语语法：Regel funcName gegeben param as Ganzzahl:
            // Canonicalizer 将 "gegeben" → "given"、"Ganzzahl" → "Int"
            String source = """
                Modul TestModul.
                Regel bewerten gegeben punktzahl as Ganzzahl:
                  gib zurueck punktzahl.
                """;

            var result = ParameterSchemaExtractor.extractSchema(source, null, "de-DE");

            assertThat(result.success()).isTrue();
            assertThat(result.parameters()).hasSize(1);

            var param = result.parameters().get(0);
            assertThat(param.name()).isEqualTo("punktzahl");
            // Canonicalizer 将 "Ganzzahl" 翻译为 "Int"
            assertThat(param.type()).isEqualTo("Int");
            assertThat(param.typeKind()).isEqualTo(ParameterSchemaExtractor.TypeKind.PRIMITIVE);
        }
    }

    // ─── 错误处理 ─────────────────────────────────────────────

    @Nested
    class ErrorHandlingTest {

        @Test
        void invalidSource_shouldReturnError() {
            var result = ParameterSchemaExtractor.extractSchema("not valid cnl", null, "en-US");
            assertThat(result.success()).isFalse();
            assertThat(result.error()).isNotNull();
        }

        @Test
        void emptySource_shouldReturnError() {
            var result = ParameterSchemaExtractor.extractSchema("", null, "en-US");
            assertThat(result.success()).isFalse();
        }
    }
}
