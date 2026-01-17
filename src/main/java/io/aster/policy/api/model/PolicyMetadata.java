package io.aster.policy.api.model;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 策略元数据，缓存策略类及其方法的反射信息。
 */
public class PolicyMetadata {

    private final Class<?> policyClass;
    private final Method method;
    private final MethodHandle methodHandle;
    private final Parameter[] parameters;

    public PolicyMetadata(Class<?> policyClass,
                          Method method,
                          MethodHandle methodHandle,
                          Parameter[] parameters) {
        this.policyClass = policyClass;
        this.method = method;
        this.methodHandle = methodHandle;
        this.parameters = parameters;
    }

    public Class<?> getPolicyClass() {
        return policyClass;
    }

    public Method getMethod() {
        return method;
    }

    public MethodHandle getMethodHandle() {
        return methodHandle;
    }

    public Parameter[] getParameters() {
        return parameters;
    }
}
