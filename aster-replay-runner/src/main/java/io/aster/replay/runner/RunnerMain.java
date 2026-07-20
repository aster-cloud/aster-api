package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.api.model.DecisionTrace;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.replay.ReplayMetadata;
import io.aster.replay.core.ExecutionPhaseResult;
import io.aster.replay.core.ReplayExecutionCore;
import io.aster.replay.core.ReplayExecutionRequest;

import java.io.InputStream;
import java.io.PrintStream;

/**
 * standalone β runner 入口。读 stdin JSON（schema ②）→ :replay 三阶段 →
 * 向 stdout 输出结果 envelope（最后一行完整 JSON），前置日志走 stderr；
 * 成功 exit 0 / 错误 exit≠0。★错误独立 envelope 不进 ReplayMetadata。
 */
public final class RunnerMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private RunnerMain() {}

    public static void main(String[] args) {
        int code = run(System.in, System.out);
        System.exit(code);
    }

    /** 便于测试的入口：不调 System.exit，返回 exit code。 */
    public static int run(InputStream in, PrintStream out) {
        RunnerEnvelope envelope;
        try {
            RunnerRequest req = MAPPER.readValue(in, RunnerRequest.class);
            envelope = execute(req);
        } catch (Exception e) {
            // 顶层兜底：请求解析或未预期异常 → INTERNAL 错误 envelope
            envelope = RunnerEnvelope.error("INTERNAL", String.valueOf(e.getMessage()), "parse");
        }
        try {
            out.println(MAPPER.writeValueAsString(envelope));   // envelope = stdout 最后一行
        } catch (Exception e) {
            System.err.println("failed to serialize envelope: " + e);
            return 3;
        }
        return "SUCCESS".equals(envelope.outcome()) ? 0 : 1;
    }

    /**
     * 三阶段执行。参考 aster-api {@code PolicyEvaluationResource:522-603} 的参考序列，
     * 但用非 CDI {@link StandaloneReplayExecutor} + {@link RunnerToolchainId}（无注入）。
     * 阶段一 {@link ReplayExecutionCore#execute} 会把 executor 异常原样透传（见该类注释），
     * 由本方法 catch → {@link #mapError} 按 phase 映射错误 envelope。
     */
    private static RunnerEnvelope execute(RunnerRequest req) {
        ReplayExecutionCore core = new ReplayExecutionCore();
        StandaloneReplayExecutor executor = new StandaloneReplayExecutor();
        try {
            ReplayExecutionRequest coreReq = toCoreRequest(req);
            ExecutionPhaseResult phase = core.execute(coreReq, executor);
            // ★captureTrace=true：生产 replayCapture 路径用 (trace || effectiveReplayCapture)=true
            //   （PolicyEvaluationResource:576），traceHash 非 null。runner parity 必须同 true，
            //   否则 traceHash 分叉必挂（Codex 抓的真陷阱）。
            DecisionTrace trace = core.buildDecisionTrace(
                phase.execResult(), phase.traceDrainResult(), /* captureTrace */ true);
            ReplayMetadata rm = core.computeReplayMetadata(
                RunnerToolchainId.current(), /* context */ req.input(),
                phase.execResult(), trace, phase.traceDrainResult());
            return RunnerEnvelope.success(rm);
        } catch (Exception e) {
            return mapError(e);
        }
    }

    /**
     * RunnerRequest → ReplayExecutionRequest（11 字段，见 {@link ReplayExecutionRequest}）。
     * ★effectiveReplayCapture=true + trace=true：runner 的职责就是复现生产 replayCapture 路径，
     *   须与生产捕获的 ReplayMetadata 对齐（含 traceHash）。aliasesTrusted=false（runner 无 HMAC
     *   上下文，MVP 无签名——别名不受信，与 parity corpus 的 import-free/无别名子集一致）。
     *   legacyEvaluateSentinel=false。vocabulary=null（raw，core 内建 index，null 合法退化）。
     */
    private static ReplayExecutionRequest toCoreRequest(RunnerRequest req) {
        return new ReplayExecutionRequest(
            req.tenantId(),
            req.source(),
            /* context */ req.input(),
            req.functionName(),
            req.locale(),
            /* vocabulary */ null,
            req.aliasSet(),
            /* legacyEvaluateSentinel */ false,
            /* aliasesTrusted */ false,
            /* trace */ true,
            /* effectiveReplayCapture */ true);
    }

    /**
     * 异常 → 错误 envelope。对齐 aster-api 四类映射（PolicyEvaluationResource 的 catch 分类）：
     * <ul>
     *   <li>{@link DynamicCnlExecutor.ModuleExecutionException}→MODULE。★它是
     *       {@link DynamicCnlExecutor.DynamicExecutionException} 的子类，必须先于父类 catch，
     *       否则 MODULE 会被误分类为 EXECUTION。诊断消息经 {@code resolutionException()} 解包
     *       （镜像 PolicyEvaluationResource:618-622：code + message）。</li>
     *   <li>{@link DynamicCnlExecutor.DynamicExecutionException}（含 AmbiguousEntryException、
     *       以及内部已把 CnlParseException 包装进来的执行失败）→EXECUTION。</li>
     *   <li>{@link InProcessCnlParser.CnlParseException}→PARSE（防御性：直解析路径；
     *       经 executor 时已被包装为 DynamicExecutionException 不会裸达此处）。</li>
     *   <li>其余 → INTERNAL。</li>
     * </ul>
     * ★import 策略在 StandaloneReplayExecutor 的 (null,true) 下：DynamicCnlExecutor.java:444
     *   因 moduleResolver==null 抛 ModuleResolutionException，被 :402-403 包装为
     *   ModuleExecutionException——所以 runner 只会见到 ModuleExecutionException，绝不见裸
     *   ModuleResolutionException。
     */
    private static RunnerEnvelope mapError(Exception e) {
        if (e instanceof DynamicCnlExecutor.ModuleExecutionException moduleEx) {
            var moduleError = moduleEx.resolutionException();
            String message = moduleError.code() + ": " + moduleError.getMessage();
            return RunnerEnvelope.error("MODULE", message, "execute");
        }
        if (e instanceof DynamicCnlExecutor.DynamicExecutionException) {
            return RunnerEnvelope.error("EXECUTION", String.valueOf(e.getMessage()), "execute");
        }
        if (e instanceof InProcessCnlParser.CnlParseException) {
            return RunnerEnvelope.error("PARSE", String.valueOf(e.getMessage()), "parse");
        }
        return RunnerEnvelope.error("INTERNAL", String.valueOf(e.getMessage()), "execute");
    }
}
