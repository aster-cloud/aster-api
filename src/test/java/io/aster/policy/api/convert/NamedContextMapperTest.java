package io.aster.policy.api.convert;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命名参数上下文映射器测试
 */
class NamedContextMapperTest {

    /**
     * 创建测试用的参数列表
     */
    private List<CoreModel.Param> createParams(String... names) {
        return java.util.Arrays.stream(names)
            .map(name -> {
                CoreModel.Param param = new CoreModel.Param();
                param.name = name;
                param.type = null; // 测试不需要类型
                return param;
            })
            .toList();
    }

    @Test
    void testNamedFormatMapping() {
        // 函数定义：func(申请, 年龄)
        List<CoreModel.Param> params = createParams("申请", "年龄");

        // 命名格式上下文
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001", "金额", 50000));
        context.put("年龄", 25);

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertEquals(2, result.positionalArgs().length);
        assertEquals(Map.of("编号", "A001", "金额", 50000), result.positionalArgs()[0]);
        assertEquals(25, result.positionalArgs()[1]);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testPositionalFormatMapping() {
        // 函数定义：func(申请, 年龄)
        List<CoreModel.Param> params = createParams("申请", "年龄");

        // 位置格式上下文
        List<Object> context = List.of(
            Map.of("编号", "A001", "金额", 50000),
            25
        );

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertFalse(result.wasNamedFormat());
        assertEquals(2, result.positionalArgs().length);
    }

    @Test
    void testSingleMapFallback() {
        // 函数定义：func(申请)
        List<CoreModel.Param> params = createParams("申请");

        // 单个 Map，键与参数名匹配
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001"));

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertEquals(1, result.positionalArgs().length);
    }

    @Test
    void testLegacySingleObjectFormat() {
        // 函数定义：func(data)
        List<CoreModel.Param> params = createParams("data");

        // 传入的 Map 键与参数名不匹配（旧版单对象格式）
        Map<String, Object> context = new HashMap<>();
        context.put("编号", "A001");
        context.put("金额", 50000);

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertFalse(result.wasNamedFormat()); // 不是命名格式
        assertEquals(1, result.positionalArgs().length);
        // 整个 Map 作为第一个参数
        assertEquals(context, result.positionalArgs()[0]);
    }

    @Test
    void testMissingParameter() {
        // 函数定义：func(申请, 年龄)
        List<CoreModel.Param> params = createParams("申请", "年龄");

        // 命名格式上下文，缺少 "年龄"
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001"));

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertFalse(result.success());
        assertTrue(result.hasError());
        assertTrue(result.error().contains("年龄"));
    }

    @Test
    void testUnknownParameterWarning() {
        // 函数定义：func(申请)
        List<CoreModel.Param> params = createParams("申请");

        // 命名格式上下文，包含未知参数
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001"));
        context.put("未知参数", "some value");

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().get(0).contains("未知参数"));
    }

    @Test
    void testNullContext() {
        List<CoreModel.Param> params = createParams("申请");

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(null, params);

        assertTrue(result.success());
        assertEquals(0, result.positionalArgs().length);
    }

    @Test
    void testEmptyParams() {
        Map<String, Object> context = Map.of("key", "value");

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, List.of());

        assertTrue(result.success());
        assertEquals(1, result.positionalArgs().length);
    }

    @Test
    void testEnglishNamedFormat() {
        // 函数定义：func(application, age)
        List<CoreModel.Param> params = createParams("application", "age");

        // 命名格式上下文
        Map<String, Object> context = new HashMap<>();
        context.put("application", Map.of("id", "A001", "amount", 50000));
        context.put("age", 25);

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertEquals(2, result.positionalArgs().length);
    }
}
