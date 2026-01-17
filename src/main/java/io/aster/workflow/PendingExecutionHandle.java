package io.aster.workflow;

import aster.runtime.workflow.ExecutionHandle;

import java.util.concurrent.CompletableFuture;

/**
 * 待完成的执行句柄
 *
 * 表示一个正在执行的 workflow，结果通过 CompletableFuture 异步获取。
 */
public class PendingExecutionHandle implements ExecutionHandle {

    private final String workflowId;
    private final CompletableFuture<Object> resultFuture;
    private volatile boolean cancelled = false;

    /**
     * 构造执行句柄
     *
     * @param workflowId workflow 唯一标识符
     * @param resultFuture 结果 future
     */
    public PendingExecutionHandle(String workflowId, CompletableFuture<Object> resultFuture) {
        this.workflowId = workflowId;
        this.resultFuture = resultFuture;
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
        if (!cancelled) {
            cancelled = true;
            resultFuture.cancel(true);
        }
    }

    /**
     * 判断是否已取消
     *
     * @return true 如果已取消
     */
    public boolean isCancelled() {
        return cancelled;
    }
}
