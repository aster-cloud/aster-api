package io.aster.llm.service;

import io.aster.llm.model.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PolicyCompileValidator 单元测试
 *
 * 覆盖：有效 CNL 校验、语法错误、markdown 清理、空输入
 */
class PolicyCompileValidatorTest {

    private PolicyCompileValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PolicyCompileValidator();
    }

    @Nested
    class Validate {

        @Test
        void 有效英文策略_应通过校验() {
            String source = """
                Module aster.test.greeting.

                Rule greet given name as Text:
                  Return "Hello, " plus name.
                """;

            ValidationResult result = validator.validate(source, "en-US");

            assertThat(result.ok()).isTrue();
            assertThat(result.moduleName()).isEqualTo("aster.test.greeting");
            assertThat(result.functionName()).isEqualTo("greet");
            assertThat(result.errors()).isEmpty();
        }

        @Test
        void 有效中文策略_应通过校验() {
            String source = """
                Module aster.test.greeting.

                Rule greet given name as Text:
                  Return "Hello, " plus name.
                """;

            ValidationResult result = validator.validate(source, "zh-CN");

            assertThat(result.ok()).isTrue();
        }

        @Test
        void 语法错误_应返回失败() {
            String source = "this is not valid cnl";

            ValidationResult result = validator.validate(source, "en-US");

            assertThat(result.ok()).isFalse();
            assertThat(result.errors()).isNotEmpty();
        }

        @Test
        void 空输入_应返回失败() {
            ValidationResult result = validator.validate("", "en-US");

            assertThat(result.ok()).isFalse();
            assertThat(result.errors()).contains("策略代码为空");
        }

        @Test
        void null输入_应返回失败() {
            ValidationResult result = validator.validate(null, "en-US");

            assertThat(result.ok()).isFalse();
            assertThat(result.errors()).contains("策略代码为空");
        }
    }

    @Nested
    class CleanLlmOutput {

        @Test
        void markdown三反引号_应被去除() {
            String source = """
                ```aster
                Module aster.test.
                Rule greet given name as Text:
                  Return name.
                ```""";

            String cleaned = validator.cleanLlmOutput(source);

            assertThat(cleaned).doesNotContain("```");
            assertThat(cleaned).startsWith("Module");
        }

        @Test
        void 纯代码块_应被去除() {
            String source = """
                ```
                Module aster.test.
                ```""";

            String cleaned = validator.cleanLlmOutput(source);

            assertThat(cleaned).doesNotContain("```");
            assertThat(cleaned).contains("Module");
        }

        @Test
        void 无markdown标记_应原样返回() {
            String source = "Module aster.test.\nRule greet given name as Text:\n  Return name.";

            String cleaned = validator.cleanLlmOutput(source);

            assertThat(cleaned).isEqualTo(source);
        }

        @Test
        void 带前后空白_应被trim() {
            String source = "\n\n  Module aster.test.  \n\n";

            String cleaned = validator.cleanLlmOutput(source);

            assertThat(cleaned).isEqualTo("Module aster.test.");
        }

        @Test
        void markdown标记中嵌入有效CNL_校验应通过() {
            String source = """
                ```aster
                Module aster.test.example.

                Rule greet given name as Text:
                  Return "Hello, " plus name.
                ```""";

            ValidationResult result = validator.validate(source, "en-US");

            assertThat(result.ok()).isTrue();
            assertThat(result.moduleName()).isEqualTo("aster.test.example");
        }
    }
}
