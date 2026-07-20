package io.aster.replay.core.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ReplayMappers#DEFAULT} 与 aster-api {@code io.aster.common.JacksonMappers.DEFAULT}
 * 的序列化 byte-parity 守门测试（S2-1a-1 Task 2）。
 *
 * <p>★core 不能反依赖 aster-api，故不能直接对比两个 mapper 的实时输出；改用**捕获的 golden
 * 字符串**：golden 是在 aster-api 侧用真实 {@code JacksonMappers.DEFAULT.writeValueAsString(fixture)}
 * 对每个 fixture 实际跑出的输出（非手推/猜测），写死为下方 expected 常量。
 *
 * <p>覆盖影响 ReplayMetadata 哈希的关键序列化维度：decimal/E-notation 数值形态、Map key
 * 顺序（含插入序与无序 Map 的真实行为）、Unicode/locale 字符、null 字段省略与否、
 * 嵌套 list-map 结构、TreeMap 有序 canonical 输入（对应 lexicon 指纹/缓存 key payload 形态）、
 * 类 Core IR JSON 结构。
 *
 * <p>golden 一旦捕获即写死；若未来 {@code JacksonMappers.DEFAULT} 配置发生变化导致此测试变红，
 * 这正是本测试的守门意义——提醒 {@code ReplayMappers} 必须同步更新以保持 byte-parity。
 */
class ReplayMappersParityTest {

    @Test
    @DisplayName("decimal / E-notation 数值形态与 JacksonMappers.DEFAULT 逐字节一致")
    void decimalAndENotationParity() throws Exception {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("a", 1.5E10);
        fixture.put("b", 0.1);
        fixture.put("c", 100.50);

        String actual = ReplayMappers.DEFAULT.writeValueAsString(fixture);

        assertEquals("{\"a\":1.5E10,\"b\":0.1,\"c\":100.5}", actual);
    }

    @Test
    @DisplayName("Map key 插入顺序与 JacksonMappers.DEFAULT 逐字节一致")
    void mapKeyOrderingParity() throws Exception {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("zebra", 1);
        fixture.put("apple", 2);
        fixture.put("middle", 3);

        String actual = ReplayMappers.DEFAULT.writeValueAsString(fixture);

        assertEquals("{\"zebra\":1,\"apple\":2,\"middle\":3}", actual);
    }

    @Test
    @DisplayName("Unicode / locale 字符与 JacksonMappers.DEFAULT 逐字节一致")
    void unicodeParity() throws Exception {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("zh", "策略引擎");
        fixture.put("de", "Straße");
        fixture.put("emoji", "✅");

        String actual = ReplayMappers.DEFAULT.writeValueAsString(fixture);

        assertEquals("{\"zh\":\"策略引擎\",\"de\":\"Straße\",\"emoji\":\"✅\"}", actual);
    }

    @Test
    @DisplayName("null 字段序列化（不省略）与 JacksonMappers.DEFAULT 逐字节一致")
    void nullFieldParity() throws Exception {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("present", "value");
        fixture.put("missing", null);

        String actual = ReplayMappers.DEFAULT.writeValueAsString(fixture);

        assertEquals("{\"present\":\"value\",\"missing\":null}", actual);
    }

    @Test
    @DisplayName("嵌套 list-map 结构与 JacksonMappers.DEFAULT 逐字节一致")
    void nestedListMapParity() throws Exception {
        Map<String, Object> fixture = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("id", "A001");
        nested.put("amount", 50000);
        fixture.put("request", nested);
        fixture.put("items", List.of(1, 2, 3));
        fixture.put("age", 25);

        String actual = ReplayMappers.DEFAULT.writeValueAsString(fixture);

        assertEquals(
            "{\"request\":{\"id\":\"A001\",\"amount\":50000},\"items\":[1,2,3],\"age\":25}",
            actual);
    }

    @Test
    @DisplayName("TreeMap 有序 canonical 输入（对应 lexicon 指纹/缓存 key payload 形态）逐字节一致")
    void treeMapCanonicalInputParity() throws Exception {
        TreeMap<String, Object> fixture = new TreeMap<>();
        fixture.put("source", "Module probe.\nRule main given x as Int, produce Int:\n  Return x.");
        fixture.put("locale", "zh-CN");
        fixture.put("aliasSet", null);
        fixture.put("identifierIndex", null);
        fixture.put("aliasesTrusted", true);
        fixture.put("lexicon", "{\"id\":\"zh-cn\"}");

        String actual = ReplayMappers.DEFAULT.writeValueAsString(fixture);

        assertEquals(
            "{\"aliasSet\":null,\"aliasesTrusted\":true,\"identifierIndex\":null,"
                + "\"lexicon\":\"{\\\"id\\\":\\\"zh-cn\\\"}\",\"locale\":\"zh-CN\","
                + "\"source\":\"Module probe.\\nRule main given x as Int, produce Int:\\n  Return x.\"}",
            actual);
    }

    @Test
    @DisplayName("类 Core IR JSON 结构与 JacksonMappers.DEFAULT 逐字节一致")
    void coreIrLikeStructureParity() throws Exception {
        // ★用 LinkedHashMap 而非 Map.of：JDK 小型不可变 Map（Map1/Map2...）的迭代顺序
        // 受进程内随机盐影响，同一 JVM 内稳定但跨 JVM 启动不保证一致，会让本测试假红/假绿。
        // fixture 本身须是确定性插入序才能验证「mapper 行为」而非「JVM 内部 Map 实现细节」。
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("name", "probe");
        Map<String, Object> func = new LinkedHashMap<>();
        func.put("name", "main");
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("name", "x");
        param.put("type", "Int");
        func.put("params", List.of(param));
        func.put("returnType", "Int");
        fixture.put("decls", List.of(func));

        String actual = ReplayMappers.DEFAULT.writeValueAsString(fixture);

        assertEquals(
            "{\"name\":\"probe\",\"decls\":[{\"name\":\"main\","
                + "\"params\":[{\"name\":\"x\",\"type\":\"Int\"}],\"returnType\":\"Int\"}]}",
            actual);
    }
}
