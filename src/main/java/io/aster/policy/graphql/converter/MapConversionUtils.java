package io.aster.policy.graphql.converter;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Map 与 POJO 转换的通用工具，减少各转换器中重复的键值处理与反射访问。
 */
public final class MapConversionUtils {

    private MapConversionUtils() {
        // 工具类不应被实例化
    }

    /**
     * 从 Map 中读取必填字段，并验证类型。
     */
    public static <T> T getRequired(Map<?, ?> map, String key, Class<T> type) {
        Objects.requireNonNull(map, "源 Map 不能为空");
        Objects.requireNonNull(key, "字段名称不能为空");
        Objects.requireNonNull(type, "目标类型不能为空");
        Object value = map.get(key);
        return castValue(value, type, true, "字段 " + key);
    }

    /**
     * 从 Map 中读取可选字段，缺失时返回默认值。
     */
    public static <T> T getOptional(Map<?, ?> map, String key, Class<T> type, T defaultValue) {
        Objects.requireNonNull(map, "源 Map 不能为空");
        Objects.requireNonNull(key, "字段名称不能为空");
        Objects.requireNonNull(type, "目标类型不能为空");
        Object value = map.get(key);
        T converted = castValue(value, type, false, "字段 " + key);
        return converted != null ? converted : defaultValue;
    }

    /**
     * 以键值对形式快速构造 Map，自动转换键为字符串，保持插入顺序。
     */
    public static Map<String, Object> buildMap(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("构造 Map 时需要偶数个键值参数");
        }
        Map<String, Object> result = new LinkedHashMap<>(keyValues.length / 2);
        for (int i = 0; i < keyValues.length; i += 2) {
            Object key = keyValues[i];
            String keyName = key == null ? "null" : String.valueOf(key);
            result.put(keyName, keyValues[i + 1]);
        }
        return result;
    }

    /**
     * 通过反射读取对象字段，返回原始值。
     */
    public static Object getFieldValue(Object obj, String fieldName) {
        return getFieldValue(obj, fieldName, Object.class);
    }

    /**
     * 通过反射读取对象字段并转换为指定类型。
     */
    public static <T> T getFieldValue(Object obj, String fieldName, Class<T> type) {
        Objects.requireNonNull(obj, "目标对象不能为空");
        Objects.requireNonNull(fieldName, "字段名称不能为空");
        Objects.requireNonNull(type, "目标类型不能为空");

        Field field = findField(obj.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        boolean accessible = field.canAccess(obj);
        try {
            if (!accessible) {
                field.setAccessible(true);
            }
            Object value = field.get(obj);
            return castValue(value, type, false, "字段 " + fieldName);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("读取字段失败: " + fieldName, e);
        } finally {
            if (!accessible) {
                field.setAccessible(false);
            }
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static <T> T castValue(Object value, Class<T> targetType, boolean required, String label) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(label + " 缺失");
            }
            return null;
        }
        if (targetType.isInstance(value)) {
            return targetType.cast(value);
        }
        if (targetType == String.class) {
            return targetType.cast(String.valueOf(value));
        }
        throw new IllegalArgumentException(label + " 类型不匹配，期望 " + targetType.getSimpleName()
            + " 实际 " + value.getClass().getName());
    }
}
