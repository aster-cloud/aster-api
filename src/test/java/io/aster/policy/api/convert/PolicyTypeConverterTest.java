package io.aster.policy.api.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aster.validation.schema.SchemaValidationException;
import io.aster.validation.semantic.SemanticValidationException;
import io.aster.policy.api.testdata.LoanApplicationWithConstraints;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PolicyTypeConverterTest {

    @Inject
    PolicyTypeConverter policyTypeConverter;

    @Test
    void testConstructFromMap_unknownFieldRejected() {
        Parameter[] parameters = Holder.class.getConstructors()[0].getParameters();

        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        input.put("count", 5);
        input.put("extra", true);

        assertThatThrownBy(() -> policyTypeConverter.prepareArguments(parameters, new Object[]{input}))
            .isInstanceOf(SchemaValidationException.class)
            .hasMessageContaining("未知字段 [extra]");
    }

    @Test
    void testConstructFromMap_missingFieldDefaultValue() throws Exception {
        Parameter[] parameters = Holder.class.getConstructors()[0].getParameters();

        Map<String, Object> input = new HashMap<>();
        input.put("name", "Alice");
        input.put("count", 5);

        Object[] args = policyTypeConverter.prepareArguments(parameters, new Object[]{input});
        assertThat(args).hasSize(1);

        SampleData data = (SampleData) args[0];
        assertThat(data.name()).isEqualTo("Alice");
        assertThat(data.count()).isEqualTo(5);
        assertThat(data.enabled()).isFalse();
    }

    public static class Holder {
        private final SampleData data;

        public Holder(SampleData data) {
            this.data = data;
        }
    }

    @Test
    void testConstructFromMap_semanticViolationRejected() {
        Parameter[] parameters = LoanHolder.class.getConstructors()[0].getParameters();

        Map<String, Object> input = new HashMap<>();
        input.put("applicantId", "app-1");
        input.put("amount", 500);
        input.put("termMonths", 24);
        input.put("purpose", "home");

        assertThatThrownBy(() -> policyTypeConverter.prepareArguments(parameters, new Object[]{input}))
            .isInstanceOf(SemanticValidationException.class)
            .hasMessageContaining("amount");
    }

    @Test
    void testConstructFromMap_semanticSatisfied() throws Exception {
        Parameter[] parameters = LoanHolder.class.getConstructors()[0].getParameters();

        Map<String, Object> input = new HashMap<>();
        input.put("applicantId", "app-1");
        input.put("amount", 20_000);
        input.put("termMonths", 36);
        input.put("purpose", "home");

        Object[] args = policyTypeConverter.prepareArguments(parameters, new Object[]{input});
        assertThat(args).hasSize(1);

        LoanApplicationWithConstraints loan = (LoanApplicationWithConstraints) args[0];
        assertThat(loan.getApplicantId()).isEqualTo("app-1");
        assertThat(loan.getAmount()).isEqualTo(20_000);
        assertThat(loan.getTermMonths()).isEqualTo(36);
        assertThat(loan.getPurpose()).isEqualTo("home");
    }

    @Test
    void testConstructFromMap_schemaAndSemanticCombined() {
        Parameter[] parameters = LoanHolder.class.getConstructors()[0].getParameters();

        Map<String, Object> input = new HashMap<>();
        input.put("applicantId", "app-1");
        input.put("amount", 20_000);
        input.put("termMonths", 36);
        input.put("purpose", "home");
        input.put("unexpected", "value");

        assertThatThrownBy(() -> policyTypeConverter.prepareArguments(parameters, new Object[]{input}))
            .isInstanceOf(SchemaValidationException.class)
            .hasMessageContaining("未知字段 [unexpected]");
    }

    /**
     * 测试数据类型，提供只读访问方法以便断言默认值。
     */
    public static class SampleData {
        private final String name;
        private final int count;
        private final boolean enabled;

        public SampleData(String name, int count, boolean enabled) {
            this.name = name;
            this.count = count;
            this.enabled = enabled;
        }

        String name() {
            return name;
        }

        int count() {
            return count;
        }

        boolean enabled() {
            return enabled;
        }
    }

    public static class LoanHolder {
        private final LoanApplicationWithConstraints application;

        public LoanHolder(LoanApplicationWithConstraints application) {
            this.application = application;
        }
    }
}
