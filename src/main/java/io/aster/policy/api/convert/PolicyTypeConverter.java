package io.aster.policy.api.convert;

import io.aster.policy.api.validation.QuarkusValidationAdapter;
import io.aster.validation.metadata.ConstructorMetadata;
import io.aster.validation.metadata.ConstructorMetadataCache;
import io.aster.validation.schema.SchemaValidator;
import io.aster.validation.semantic.SemanticValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.Map;

/**
 * 策略参数类型转换器，集中处理上下文映射。
 */
@ApplicationScoped
public class PolicyTypeConverter {

    private final ConstructorMetadataCache constructorMetadataCache;
    private final SchemaValidator schemaValidator;
    private final SemanticValidator semanticValidator;

    @Inject
    public PolicyTypeConverter(ConstructorMetadataCache constructorMetadataCache,
                               QuarkusValidationAdapter adapter) {
        this.constructorMetadataCache = constructorMetadataCache;
        this.schemaValidator = adapter.getSchemaValidator();
        this.semanticValidator = adapter.getSemanticValidator();
    }

    public Object[] prepareArguments(Parameter[] parameters, Object[] context) throws Exception {
        if (parameters.length == 0) {
            return new Object[0];
        }

        Object[] safeContext = context == null ? new Object[0] : context;
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> expectedType = parameters[i].getType();
            Object contextObj = i < safeContext.length ? safeContext[i] : null;

            if (contextObj == null) {
                args[i] = defaultForMissingParameter(expectedType);
                continue;
            }

            if (contextObj instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) rawMap;

                if (isPrimitiveOrWrapper(expectedType)) {
                    Object value = map.values().stream().findFirst().orElse(null);
                    args[i] = convertValue(value, expectedType);
                } else if (Map.class.isAssignableFrom(expectedType)) {
                    args[i] = map.isEmpty() ? Collections.emptyMap() : map;
                } else {
                    args[i] = constructFromMap(expectedType, map);
                }
            } else {
                args[i] = convertValue(contextObj, expectedType);
            }
        }
        return args;
    }

    private Object defaultForMissingParameter(Class<?> expectedType) {
        if (expectedType == String.class) {
            return "";
        }
        if (expectedType.isPrimitive() || isPrimitiveOrWrapper(expectedType)) {
            return getDefaultValue(expectedType);
        }
        if (Map.class.isAssignableFrom(expectedType)) {
            return Collections.emptyMap();
        }
        return null;
    }

    private boolean isPrimitiveOrWrapper(Class<?> type) {
        return type.isPrimitive() ||
               type == Integer.class || type == Long.class ||
               type == Double.class || type == Float.class ||
               type == Boolean.class || type == Character.class ||
               type == Byte.class || type == Short.class ||
               type == String.class;
    }

    private Object constructFromMap(Class<?> targetClass, Map<String, Object> map) throws Exception {
        ConstructorMetadata metadata = constructorMetadataCache.getConstructorMetadata(targetClass);

        // 在构造目标对象前先执行 Schema 校验，以确保输入字段与目标类型匹配
        schemaValidator.validateSchema(targetClass, map);

        var parameters = metadata.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            args[i] = getDefaultValue(parameters[i].getType());
        }

        for (Map.Entry<String, Integer> entry : metadata.getFieldNameToParameterIndex().entrySet()) {
            Integer index = entry.getValue();
            if (index == null || index < 0 || index >= parameters.length) {
                continue;
            }
            Class<?> paramType = parameters[index].getType();
            Object value = map.get(entry.getKey());

            if (value != null) {
                args[index] = convertValue(value, paramType);
            }
        }

        Object instance = metadata.getConstructor().newInstance(args);
        // 在返回对象前执行 Layer 2 语义约束验证，确保构造出的对象满足业务规则
        semanticValidator.validateSemantics(instance);
        return instance;
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return getDefaultValue(targetType);
        }

        if (targetType.isInstance(value)) {
            return value;
        }

        if (value instanceof Number num) {
            if (targetType == int.class || targetType == Integer.class) {
                return num.intValue();
            } else if (targetType == long.class || targetType == Long.class) {
                return num.longValue();
            } else if (targetType == double.class || targetType == Double.class) {
                return num.doubleValue();
            } else if (targetType == float.class || targetType == Float.class) {
                return num.floatValue();
            } else if (targetType == short.class || targetType == Short.class) {
                return num.shortValue();
            } else if (targetType == byte.class || targetType == Byte.class) {
                return num.byteValue();
            }
        }

        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean) {
                return value;
            }
            return Boolean.parseBoolean(value.toString());
        }

        if (targetType == String.class) {
            return value.toString();
        }

        return value;
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0.0D;
        if (type == float.class || type == Float.class) return 0.0F;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == char.class || type == Character.class) return '\0';
        if (type == String.class) return "";
        return null;
    }
}
