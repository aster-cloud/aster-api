package io.aster.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 确定性决策快照（时钟/UUID/随机数）。
 *
 * 负责将 ReplayDeterministicClock/Uuid/Random 的录制决策序列序列化为 JSON，
 * 以支撑 workflow 重放时恢复完整的确定性上下文。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeterminismSnapshot {

    /**
     * clockTimes 与 uuids 均限制 500 条，randoms 每个 source 限制 500 条。
     */
    private static final int MAX_CLOCK_RECORDS = 500;
    private static final int MAX_UUID_RECORDS = 500;
    private static final int MAX_RANDOM_RECORDS_PER_SOURCE = 500;

    @JsonProperty("clockTimes")
    private List<String> clockTimes;

    @JsonProperty("uuids")
    private List<String> uuids;

    @JsonProperty("randoms")
    private Map<String, List<Long>> randoms;

    /**
     * 工厂方法：从确定性门面收集快照数据。
     *
     * @param clock 确定性时钟（可为 null）
     * @param uuid 确定性 UUID 门面（可为 null）
     * @param random 确定性随机门面（可为 null）
     * @return 决策快照
     */
    public static DeterminismSnapshot from(
            ReplayDeterministicClock clock,
            ReplayDeterministicUuid uuid,
            ReplayDeterministicRandom random
    ) {
        DeterminismSnapshot snapshot = new DeterminismSnapshot();
        if (clock != null) {
            List<Instant> times = clock.getRecordedTimes();
            if (!times.isEmpty()) {
                snapshot.clockTimes = times.stream()
                        .limit(MAX_CLOCK_RECORDS)
                        .map(Instant::toString)
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        }

        if (uuid != null) {
            List<UUID> recordedUuids = uuid.getRecordedUuids();
            if (!recordedUuids.isEmpty()) {
                snapshot.uuids = recordedUuids.stream()
                        .limit(MAX_UUID_RECORDS)
                        .map(UUID::toString)
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        }

        if (random != null) {
            Map<String, List<Long>> recordedRandoms = random.getRecordedRandoms();
            if (recordedRandoms != null && !recordedRandoms.isEmpty()) {
                Map<String, List<Long>> trimmed = new LinkedHashMap<>();
                recordedRandoms.forEach((source, values) -> {
                    if (source == null || values == null || values.isEmpty()) {
                        return;
                    }
                    List<Long> limited = values.stream()
                            .limit(MAX_RANDOM_RECORDS_PER_SOURCE)
                            .collect(Collectors.toCollection(ArrayList::new));
                    if (!limited.isEmpty()) {
                        trimmed.put(source, limited);
                    }
                });
                if (!trimmed.isEmpty()) {
                    snapshot.randoms = trimmed;
                }
            }
        }

        return snapshot;
    }

    /**
     * 将快照应用到确定性门面，恢复 replay 模式。
     *
     * @param clock 确定性时钟（可为 null）
     * @param uuid 确定性 UUID 门面（可为 null）
     * @param random 确定性随机门面（可为 null）
     */
    public void applyTo(
            ReplayDeterministicClock clock,
            ReplayDeterministicUuid uuid,
            ReplayDeterministicRandom random
    ) {
        if (clock != null && clockTimes != null && !clockTimes.isEmpty()) {
            List<Instant> times = clockTimes.stream()
                    .map(Instant::parse)
                    .collect(Collectors.toList());
            if (!times.isEmpty()) {
                clock.enterReplayMode(times);
            }
        }

        if (uuid != null && uuids != null && !uuids.isEmpty()) {
            List<UUID> parsed = uuids.stream()
                    .map(UUID::fromString)
                    .collect(Collectors.toList());
            if (!parsed.isEmpty()) {
                uuid.enterReplayMode(parsed);
            }
        }

        if (random != null && randoms != null && !randoms.isEmpty()) {
            Map<String, List<Long>> copy = new LinkedHashMap<>();
            randoms.forEach((source, values) -> {
                if (source == null || values == null || values.isEmpty()) {
                    return;
                }
                copy.put(source, new ArrayList<>(values));
            });
            if (!copy.isEmpty()) {
                random.enterReplayMode(copy);
            }
        }
    }

    /**
     * 判断快照是否为空（所有字段均为空或空集合）。
     *
     * @return true 表示无数据
     */
    public boolean isEmpty() {
        return (clockTimes == null || clockTimes.isEmpty()) &&
               (uuids == null || uuids.isEmpty()) &&
               (randoms == null || randoms.isEmpty());
    }

    public List<String> getClockTimes() {
        return clockTimes;
    }

    public void setClockTimes(List<String> clockTimes) {
        this.clockTimes = copyIfNotNull(clockTimes);
    }

    public List<String> getUuids() {
        return uuids;
    }

    public void setUuids(List<String> uuids) {
        this.uuids = copyIfNotNull(uuids);
    }

    public Map<String, List<Long>> getRandoms() {
        return copyRandoms(randoms);
    }

    public void setRandoms(Map<String, List<Long>> randoms) {
        this.randoms = copyRandoms(randoms);
    }

    /**
     * 向后兼容旧字段 recordedTimes → clockTimes。
     *
     * @param recordedTimes 旧结构字段
     */
    @JsonSetter("recordedTimes")
    public void setLegacyRecordedTimes(List<String> recordedTimes) {
        if (recordedTimes == null) {
            return;
        }
        this.clockTimes = copyIfNotNull(recordedTimes);
    }

    private List<String> copyIfNotNull(List<String> source) {
        if (source == null) {
            return null;
        }
        return new ArrayList<>(source);
    }

    private Map<String, List<Long>> copyRandoms(Map<String, List<Long>> source) {
        if (source == null) {
            return null;
        }
        Map<String, List<Long>> copy = new LinkedHashMap<>();
        source.forEach((key, values) -> {
            if (key == null || values == null) {
                return;
            }
            copy.put(key, new ArrayList<>(values));
        });
        return copy;
    }
}
