package io.aster.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReplayDeterministicClock 单元测试（Phase 3.6）
 *
 * 验证确定性时钟的记录、重放、模式切换等核心逻辑。
 */
class ReplayDeterministicClockTest {

    /**
     * 测试记录与重放场景
     *
     * 验证正常执行模式记录时间，重放模式按序返回记录的时间。
     */
    @Test
    void testRecordAndReplay() {
        ReplayDeterministicClock clock = new ReplayDeterministicClock();

        // 正常执行模式：记录 3 个时间
        Instant t1 = clock.now();
        Instant t2 = clock.now();
        Instant t3 = clock.now();

        // 验证记录了 3 个时间
        List<Instant> recorded = clock.getRecordedTimes();
        assertEquals(3, recorded.size());
        assertEquals(t1, recorded.get(0));
        assertEquals(t2, recorded.get(1));
        assertEquals(t3, recorded.get(2));

        // 进入重放模式
        clock.enterReplayMode(recorded);
        assertTrue(clock.isReplayMode());

        // 重放模式：按序返回记录的时间
        assertEquals(t1, clock.now());
        assertEquals(t2, clock.now());
        assertEquals(t3, clock.now());
    }

    /**
     * 测试重放索引溢出异常
     *
     * 验证重放模式下调用次数超过记录时间数量时抛出 IllegalStateException。
     */
    @Test
    void testReplayOverflow() {
        ReplayDeterministicClock clock = new ReplayDeterministicClock();
        clock.enterReplayMode(List.of(Instant.now()));

        // 第一次调用正常
        assertNotNull(clock.now());

        // 第二次调用应抛出异常（索引溢出）
        IllegalStateException exception = assertThrows(IllegalStateException.class, clock::now);
        assertTrue(exception.getMessage().contains("Replay exhausted"));
        assertTrue(exception.getMessage().contains("requested time #1"));
        assertTrue(exception.getMessage().contains("only 1 times recorded"));
    }

    /**
     * 测试模式切换
     *
     * 验证 enterReplayMode 和 exitReplayMode 正确切换状态。
     */
    @Test
    void testModeSwitch() {
        ReplayDeterministicClock clock = new ReplayDeterministicClock();

        // 初始非重放模式
        assertFalse(clock.isReplayMode());

        // 进入重放
        clock.enterReplayMode(List.of(Instant.now()));
        assertTrue(clock.isReplayMode());

        // 退出重放
        clock.exitReplayMode();
        assertFalse(clock.isReplayMode());
        assertEquals(0, clock.getRecordedTimes().size()); // 记录已清空
    }

    /**
     * 测试 getRecordedTimes 返回副本
     *
     * 验证防御性拷贝，避免外部修改内部状态。
     */
    @Test
    void testGetRecordedTimesReturnsCopy() {
        ReplayDeterministicClock clock = new ReplayDeterministicClock();
        clock.now();

        List<Instant> recorded1 = clock.getRecordedTimes();
        List<Instant> recorded2 = clock.getRecordedTimes();

        assertNotSame(recorded1, recorded2); // 不同实例
        assertEquals(recorded1, recorded2); // 内容相同

        // 修改返回的列表不应影响内部状态
        recorded1.clear();
        assertEquals(1, clock.getRecordedTimes().size()); // 内部状态未被修改
    }

    /**
     * 测试重放模式下不记录新时间
     *
     * 验证重放模式下调用 now() 不应增加记录（防止污染）。
     */
    @Test
    void testRecordInReplayModeDoesNotPollute() {
        ReplayDeterministicClock clock = new ReplayDeterministicClock();
        Instant initialTime = Instant.now();
        clock.enterReplayMode(List.of(initialTime));

        // 重放模式下调用 now() 不应增加记录
        int sizeBefore = clock.getRecordedTimes().size();
        clock.now();
        int sizeAfter = clock.getRecordedTimes().size();

        assertEquals(sizeBefore, sizeAfter);
        assertEquals(1, sizeAfter); // 仍然只有初始的 1 条记录
    }

    /**
     * 测试空时间列表重放
     *
     * 验证使用空列表进入重放模式时，第一次调用 now() 即抛出异常。
     */
    @Test
    void testReplayWithEmptyTimesList() {
        ReplayDeterministicClock clock = new ReplayDeterministicClock();
        clock.enterReplayMode(List.of());

        assertTrue(clock.isReplayMode());

        // 空列表重放，第一次调用即溢出
        IllegalStateException exception = assertThrows(IllegalStateException.class, clock::now);
        assertTrue(exception.getMessage().contains("only 0 times recorded"));
    }

    /**
     * 测试退出重放后恢复正常记录
     *
     * 验证退出重放模式后可以继续正常记录时间。
     */
    @Test
    void testExitReplayRestoresNormalMode() {
        ReplayDeterministicClock clock = new ReplayDeterministicClock();

        // 先记录 1 个时间
        clock.now();
        assertEquals(1, clock.getRecordedTimes().size());

        // 进入并退出重放模式
        clock.enterReplayMode(List.of(Instant.now()));
        clock.exitReplayMode();

        // 退出后应清空记录并恢复正常模式
        assertFalse(clock.isReplayMode());
        assertEquals(0, clock.getRecordedTimes().size());

        // 继续记录应正常工作
        clock.now();
        assertEquals(1, clock.getRecordedTimes().size());
    }
}
