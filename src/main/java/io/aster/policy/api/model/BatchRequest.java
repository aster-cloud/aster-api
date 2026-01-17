package io.aster.policy.api.model;

/**
 * 批量评估请求，封装单次策略执行所需信息。
 */
public class BatchRequest {

    public String tenantId;
    public String policyModule;
    public String policyFunction;
    public Object[] context;

    public BatchRequest() {
    }

    public BatchRequest(String tenantId, String policyModule, String policyFunction, Object[] context) {
        this.tenantId = tenantId;
        this.policyModule = policyModule;
        this.policyFunction = policyFunction;
        this.context = context;
    }
}
