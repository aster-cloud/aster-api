package io.aster.replay.runner;

/**
 * standalone β runner 入口。首刀为骨架——后续 Task 填三阶段执行 + envelope 输出。
 * 契约：读 stdin JSON 请求 → :replay 三阶段 → 向 stdout 输出结果 envelope（最后一行完整 JSON），
 * 前置日志走 stderr；成功 exit 0 / 错误 exit≠0。
 */
public final class RunnerMain {
    private RunnerMain() {}

    public static void main(String[] args) {
        // 骨架占位：后续 Task 实现读请求→执行→输出 envelope。
        System.err.println("aster-replay-runner: skeleton");
    }
}
