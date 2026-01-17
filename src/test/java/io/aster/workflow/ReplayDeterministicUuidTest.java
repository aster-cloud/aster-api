package io.aster.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ReplayDeterministicUuid 单元测试
 */
class ReplayDeterministicUuidTest {

    @Test
    void testRecordMode() {
        ReplayDeterministicUuid generator = new ReplayDeterministicUuid();

        UUID u1 = generator.randomUUID();
        UUID u2 = generator.randomUUID();
        UUID u3 = generator.randomUUID();

        List<UUID> snapshot = generator.getRecordedUuids();
        assertThat(snapshot).containsExactly(u1, u2, u3);

        for (int i = 0; i < ReplayDeterministicUuid.MAX_RECORDS; i++) {
            generator.randomUUID();
        }

        assertThat(generator.getRecordedUuids())
                .hasSize(ReplayDeterministicUuid.MAX_RECORDS);
    }

    @Test
    void testReplayMode() {
        ReplayDeterministicUuid generator = new ReplayDeterministicUuid();
        List<UUID> uuids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        generator.enterReplayMode(uuids);

        assertThat(generator.isReplayMode()).isTrue();
        assertThat(generator.randomUUID()).isEqualTo(uuids.get(0));
        assertThat(generator.randomUUID()).isEqualTo(uuids.get(1));
        assertThat(generator.randomUUID()).isEqualTo(uuids.get(2));

        ReplayDeterministicUuid perfGenerator = new ReplayDeterministicUuid();
        long best = Long.MAX_VALUE;
        for (int attempt = 0; attempt < 10; attempt++) {
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                perfGenerator.randomUUID();
            }
            long elapsed = System.nanoTime() - start;
            if (elapsed < best) {
                best = elapsed;
            }
        }
        System.out.printf("ReplayDeterministicUuid performance best(ns)=%d%n", best);
        assertThat(best).isLessThan(1_000_000L);
    }

    @Test
    void testReplayExhausted() {
        ReplayDeterministicUuid generator = new ReplayDeterministicUuid();
        generator.enterReplayMode(List.of(UUID.randomUUID()));

        generator.randomUUID();

        IllegalStateException ex = assertThrows(IllegalStateException.class, generator::randomUUID);
        assertThat(ex.getMessage()).contains("UUID replay exhausted")
                .contains("requested UUID #1")
                .contains("only 1 recorded");
    }

    @Test
    void testModeSwitch() {
        ReplayDeterministicUuid generator = ReplayDeterministicUuid.current();
        generator.exitReplayMode();

        assertThat(generator.isReplayMode()).isFalse();

        generator.enterReplayMode(List.of(UUID.randomUUID()));
        assertThat(generator.isReplayMode()).isTrue();

        generator.exitReplayMode();
        assertThat(generator.isReplayMode()).isFalse();
        assertThat(generator.getRecordedUuids()).isEmpty();

        ReplayDeterministicUuid.clearCurrent();
    }

    @Test
    void testImmutableSnapshot() {
        ReplayDeterministicUuid generator = new ReplayDeterministicUuid();
        generator.randomUUID();

        List<UUID> snapshot = generator.getRecordedUuids();
        snapshot.clear();

        assertThat(generator.getRecordedUuids()).hasSize(1);

        List<UUID> replayPayload = new ArrayList<>();
        replayPayload.add(UUID.randomUUID());
        replayPayload.add(UUID.randomUUID());
        replayPayload.add(UUID.randomUUID());
        generator.enterReplayMode(replayPayload);

        assertThat(generator.randomUUID()).isEqualTo(replayPayload.get(0));
    }
}
