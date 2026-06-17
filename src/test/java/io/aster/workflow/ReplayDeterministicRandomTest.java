package io.aster.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayDeterministicRandomTest {

    @AfterEach
    void tearDown() {
        ReplayDeterministicRandom.clearCurrent();
    }

    @Test
    void testNextInt() {
        ReplayDeterministicRandom generator = new ReplayDeterministicRandom();
        String source = "PolicyEvaluationService:113";

        int first = generator.nextInt(source);
        int second = generator.nextInt(source);

        Map<String, List<Long>> snapshot = generator.getRecordedRandoms();
        assertThat(snapshot).containsKey(source);
        assertThat(snapshot.get(source)).hasSize(2);

        ReplayDeterministicRandom replay = new ReplayDeterministicRandom();
        replay.enterReplayMode(snapshot);

        assertThat(replay.nextInt(source)).isEqualTo(first);
        assertThat(replay.nextInt(source)).isEqualTo(second);
    }

    @Test
    void testNextLong() {
        ReplayDeterministicRandom generator = new ReplayDeterministicRandom();
        String source = "Engine:55";

        long first = generator.nextLong(source);
        long second = generator.nextLong(source);

        assertThat(generator.getRecordedRandoms().get(source))
                .containsExactly(first, second);

        String overflowSource = "Overflow:1";
        for (int i = 0; i < ReplayDeterministicRandom.MAX_RECORDS_PER_SOURCE + 10; i++) {
            generator.nextLong(overflowSource);
        }
        assertThat(generator.getRecordedRandoms().get(overflowSource))
                .hasSize(ReplayDeterministicRandom.MAX_RECORDS_PER_SOURCE);
    }

    @Test
    void testNextDouble() {
        ReplayDeterministicRandom recorder = new ReplayDeterministicRandom();
        String recordSource = "DoubleRecord";
        // nextDouble 现在返回 [0,1) 区间内均匀的 double（(bits >>> 11) * 2^-53）。
        double recorded = recorder.nextDouble(recordSource);
        assertThat(recorded).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
        assertThat(recorder.getRecordedRandoms().get(recordSource)).hasSize(1);

        // 重放：对记录的 long 应用同一公式，确定且落在 [0,1)。
        long bitsA = 0x1234_5678_9ABC_DEF0L;
        long bitsB = 0x7FFF_FFFF_FFFF_FFFFL;
        Map<String, List<Long>> payload = Map.of(
                "DoubleReplay", List.of(bitsA, bitsB)
        );
        ReplayDeterministicRandom replay = new ReplayDeterministicRandom();
        replay.enterReplayMode(payload);

        assertThat(replay.nextDouble("DoubleReplay")).isEqualTo((bitsA >>> 11) * 0x1.0p-53);
        assertThat(replay.nextDouble("DoubleReplay")).isEqualTo((bitsB >>> 11) * 0x1.0p-53);
    }

    @Test
    void testReplayWithMultipleSources() {
        Map<String, List<Long>> payload = new LinkedHashMap<>();
        long betaBits = 1L << 60;
        payload.put("Alpha:10", List.of(10L, 11L, 12L));
        payload.put("Beta:20", List.of(betaBits));
        payload.put("PolicyEvaluationService:113", List.of(30L, 31L));

        ReplayDeterministicRandom generator = new ReplayDeterministicRandom();
        generator.enterReplayMode(payload);

        assertThat(generator.nextLong("Alpha:10")).isEqualTo(10L);
        assertThat(generator.nextInt("Alpha:10")).isEqualTo((int) 11L);
        assertThat(generator.nextDouble("Beta:20")).isEqualTo((betaBits >>> 11) * 0x1.0p-53);
        assertThat(generator.nextInt("PolicyEvaluationService:113")).isEqualTo((int) 30L);
        assertThat(generator.nextLong("PolicyEvaluationService:113")).isEqualTo(31L);
    }

    @Test
    void testReplayExhausted() {
        ReplayDeterministicRandom generator = new ReplayDeterministicRandom();
        generator.enterReplayMode(Map.of("Alpha:10", List.of(1L)));

        generator.nextLong("Alpha:10");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> generator.nextLong("Alpha:10"));

        assertThat(ex.getMessage())
                .contains("Random replay exhausted")
                .contains("Alpha:10")
                .contains("#1");
    }

    @Test
    void testModeSwitch() {
        ReplayDeterministicRandom generator = ReplayDeterministicRandom.current();
        assertThat(generator.isReplayMode()).isFalse();

        generator.enterReplayMode(Map.of("Alpha:10", List.of(1L, 2L)));
        assertThat(generator.isReplayMode()).isTrue();
        assertThat(generator.nextLong("Alpha:10")).isEqualTo(1L);

        generator.exitReplayMode();
        assertThat(generator.isReplayMode()).isFalse();

        long recorded = generator.nextLong("Alpha:10");
        assertThat(generator.getRecordedRandoms().get("Alpha:10"))
                .containsExactly(recorded);
    }
}
