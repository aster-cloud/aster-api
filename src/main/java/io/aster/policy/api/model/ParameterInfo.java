package io.aster.policy.api.model;

/**
 * 策略参数信息。
 */
public class ParameterInfo {

    private final String name;
    private final String type;
    private final String fullTypeName;

    public ParameterInfo(String name, String type, String fullTypeName) {
        this.name = name;
        this.type = type;
        this.fullTypeName = fullTypeName;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getFullTypeName() {
        return fullTypeName;
    }
}
