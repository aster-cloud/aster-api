package io.aster.replay.runner;

import io.aster.policy.module.ModuleResolutionException;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.replay.core.ReplayExecutor;
import io.aster.replay.core.ReplayExecutorResult;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneReplayExecutorTest {
    @Test
    void executesImportFreePolicy() {
        ReplayExecutor exec = new StandaloneReplayExecutor();
        // ★CNL 语法用真实语料形态（ReplayExecutionCoreTest:35 实证）：带类型标注 + produce。
        // vocabIndex 传 null（无领域词汇，退化仅内置——buildVocabularyIndex 对 null 返 null，合法）；
        // aliasSet 传 null（无用户别名）。locale 用 "en-US"（真实语料用此形态）。
        ReplayExecutorResult r = exec.execute(
            "tenant-1", "Module probe.\nRule main given x as Int, produce Int:\n  Return x.",
            Map.of("x", 1), "main", "en-US", /* vocabIndex */ null, true, /* aliasSet */ null, false);
        assertNotNull(r);
        assertEquals("probe", r.moduleName());
    }

    @Test
    void importPolicyFailsClosed() {
        ReplayExecutor exec = new StandaloneReplayExecutor();
        // 跨模块 import → modulesEnabled=true + null resolver → fail-closed。
        // ★真实 CNL import 语法（DynamicCnlExecutorCacheTest/ModuleResolverIT 实证）是
        // `Use <module.path> version <N> as <Alias>.`，非 `Import other.`（brief 草稿占位语法，
        // 真编译下不识别该关键字）。
        // ★真实抛出类型是 DynamicCnlExecutor.ModuleExecutionException（非 ModuleResolutionException
        // 原样透传）——DynamicCnlExecutor.java:402-403 在 compileCoreIr 内部 catch
        // ModuleResolutionException 后包一层 ModuleExecutionException 再抛（与 aster-api
        // PolicyEvaluationResource.java:618-620 现有解包用法一致）。不变量本质不变：
        // fail-closed 的根因始终是 ModuleResolutionException，此处断言其作为 cause 出现。
        DynamicCnlExecutor.ModuleExecutionException thrown = assertThrows(
            DynamicCnlExecutor.ModuleExecutionException.class, () ->
                exec.execute("tenant-1",
                    "Module probe.\nUse risk.Scoring version 1 as Score.\nRule main given x as Int, produce Int:\n  Return x.",
                    Map.of("x", 1), "main", "en-US", null, true, null, false));
        assertInstanceOf(ModuleResolutionException.class, thrown.resolutionException());
    }
}
