package io.aster.policy.api.model;

import java.util.List;

/**
 * 策略验证结果，提供元信息。
 */
public class PolicyValidationResult {

    private final boolean valid;
    private final String message;
    private final String policyModule;
    private final String policyFunction;
    private final List<ParameterInfo> parameters;
    private final String returnType;
    private final String returnTypeFullName;

    public PolicyValidationResult(boolean valid,
                                  String message,
                                  String policyModule,
                                  String policyFunction,
                                  List<ParameterInfo> parameters,
                                  String returnType,
                                  String returnTypeFullName) {
        this.valid = valid;
        this.message = message;
        this.policyModule = policyModule;
        this.policyFunction = policyFunction;
        this.parameters = parameters;
        this.returnType = returnType;
        this.returnTypeFullName = returnTypeFullName;
    }

    public boolean isValid() {
        return valid;
    }

    public String getMessage() {
        return message;
    }

    public String getPolicyModule() {
        return policyModule;
    }

    public String getPolicyFunction() {
        return policyFunction;
    }

    public List<ParameterInfo> getParameters() {
        return parameters;
    }

    public String getReturnType() {
        return returnType;
    }

    public String getReturnTypeFullName() {
        return returnTypeFullName;
    }
}
