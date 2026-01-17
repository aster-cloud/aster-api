package io.aster.workflow;

import aster.runtime.workflow.ExecutionHandle;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;

/**
 * 已完成的执行句柄
 *
 * 表示一个已经完成的 workflow，结果立即可用。
 */
public class CompletedExecutionHandle implements ExecutionHandle {

    private final String workflowId;
    private final CompletableFuture<Object> resultFuture;

    /**
     * 构造已完成的执行句柄
     *
     * @param workflowId workflow 唯一标识符
     * @param result 执行结果（可能是 null 或 JSON 字符串）
     */
    public CompletedExecutionHandle(String workflowId, String result) {
        this.workflowId = workflowId;
        this.resultFuture = CompletableFuture.completedFuture(deserializeResult(result));
    }

    @Override
    public String getWorkflowId() {
        return workflowId;
    }

    @Override
    public CompletableFuture<Object> getResult() {
        return resultFuture;
    }

    @Override
    public void cancel() {
        // 已完成的 workflow 无法取消
        throw new UnsupportedOperationException("Cannot cancel a completed workflow");
    }

    /**
     * 反序列化结果
     *
     * @param result JSON 字符串或 null
     * @return 反序列化后的对象
     */
    private Object deserializeResult(String result) {
        if (result == null || result.isEmpty()) {
            return null;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(result, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize workflow result", e);
        }
    }
}
