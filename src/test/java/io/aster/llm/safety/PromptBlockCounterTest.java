package io.aster.llm.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBlockCounterTest {

    private final PromptBlockCounter counter = new PromptBlockCounter();

    @BeforeEach
    void reset() {
        counter.reset();
    }

    @Test
    void firstIncrementReturnsOne() {
        assertThat(counter.incrementAndGet("tenant-A")).isEqualTo(1);
    }

    @Test
    void incrementsAreIndependentPerTenant() {
        counter.incrementAndGet("tenant-A");
        counter.incrementAndGet("tenant-A");
        assertThat(counter.incrementAndGet("tenant-B")).isEqualTo(1);
        assertThat(counter.incrementAndGet("tenant-A")).isEqualTo(3);
    }

    @Test
    void monotonic() {
        for (int i = 1; i <= 5; i++) {
            assertThat(counter.incrementAndGet("t1")).isEqualTo(i);
        }
    }
}
