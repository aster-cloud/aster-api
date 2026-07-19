# S2-1a-0 ReplayExecutionCore 提取 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 aster-api 的 replay orchestration 从 JAX-RS resource 抽进新的共享 Gradle 模块 `aster-replay-core`，**行为 byte-identical 不变**——为 S2-1（β runner）复用同一份代码保 byte-parity 铺路。

**Architecture:** 新增 `aster-replay-core` 子模块，含：core 拥有的纯值类型（`ReplayExecutionRequest`/`ReplayExecutorResult`/`ExecutionPhaseResult`）、`ReplayExecutor` 纯接口、移入的 `ReplayMetadata`/`DecisionTrace` + trace glue、以及**三阶段** core API（`execute` / `buildDecisionTrace` / `computeReplayMetadata`）。aster-api 提供委托现有 `DynamicCnlExecutor` 的 `ReplayExecutor` adapter，`PolicyEvaluationResource.evaluateSource` 改为三阶段调用，**在阶段间原位交织 metrics/audit/error-mapping**。首刀**不移** `DynamicCnlExecutor`/`ModuleResolver`/Panache。

**Tech Stack:** Java 25、Gradle（首个本地子模块）、Quarkus（adapter 侧不变）、GraalVM Polyglot 25.0.3、JUnit5、aster-lang-core/truffle。

## Global Constraints

- **byte-identical 铁律**：这是纯提取重构，aster-api 对外行为（响应原始 bytes、事件顺序、异常优先级、trace/ReplayMetadata 内容）**必须逐字节不变**。验收 = 全回归绿 + `ReplayMetadataTest` 32 测绿 + `PublicApiContractTest` golden 不变 + 新增字符化测试。
- **单一权威拓扑（Codex 策略审 93 定案）**：三阶段 core API，非一次 `execute`。core `execute → ExecutionPhaseResult{execResult, traceDrainResult}`；adapter 原位做 metrics/loan/audit/log；`core.buildDecisionTrace → DecisionTrace`；adapter recordApiCall/EvaluationResponse；`core.computeReplayMetadata → ReplayMetadata`；adapter withReplayMetadata。
- **首刀不移 executor**：`DynamicCnlExecutor`/`ModuleResolver`/`InProcessCnlParser`/`NamedContextMapper`/Panache 实体**留 aster-api**。core 只定义 `ReplayExecutor` 纯接口，aster-api adapter 委托现有 executor。`ModuleGraphResolver` **不引入**（延后）。
- **跨模块 DTO 全 core 拥有**：`ReplayExecutor.execute` 返回 core 的 `ReplayExecutorResult`（`result/moduleName/functionName/executionTimeMs`），**不得**返回 `DynamicCnlExecutor.ExecutionResult`。core public API **不得引用** `DynamicCnlExecutor`/`ModuleResolver` 等 aster-api 类型（否则依赖环进签名）。
- **异常原样透传**：`ReplayExecutor`/core 不捕获/不包装 executor runtime exception；core `execute` 只 `finally` drain trace 后**抛同一异常实例**；resource 保现有四类 catch（`AmbiguousEntryException`/`ModuleExecutionException`/`DynamicExecutionException`/`CnlParseException`）+ HTTP 映射；首刀**不新增** core 统一异常、不做转换。executor 失败路径**不产** DecisionTrace/ReplayMetadata。
- **移入类型保留原 FQCN/package**：`ReplayMetadata`（`io.aster.policy.replay`）、`DecisionTrace`（`io.aster.policy.api.model`）移入 core 后**保原包名**（只改 Gradle 归属），减 Jackson/OpenAPI/测试 import/反射变化。
- **惰性/所有权铁律**：`ReplayExecutionRequest` 收 **raw vocabulary + raw aliasSet**（core 内先建 vocab index 再建 alias set，无第二种所有权）；`aliasesTrusted` adapter 派生传入（boolean）；`toolchainId` **不进** execute request，由 `computeReplayMetadata` 阶段惰性提供（final String）；locale/functionName getter **不合并求值**；`request.context()` 原始对象**分别**用于 executor/audit/replay hash，**不提前转 positional**。★`ToolchainIdentityProvider.currentToolchainId()` 引用 aster-api 的 `UserAliasValidator.VERSION`——**首刀把完整 toolchain provider 留 adapter**，只向 `computeReplayMetadata` 惰性传最终 String（core 不 import `UserAliasValidator`）。
- **TraceAccess 铁律**：arm/execute/drain **同一 worker 线程同一同步调用栈**；core **不新增** Uni/CompletionStage/线程池/异步；保留 `finally` drain；未捕获时仍先 drain 历史 ThreadLocal（`else` 分支）。
- **测试框架/命令**：`./gradlew test`（排 IT/chaos，含 `ReplayMetadataTest`/`DynamicCnlExecutor*Test`）；`./gradlew integrationTest`（Testcontainers，含 `QuotaChainIT`/`InternalCallerFilterHmacIT`）；`./gradlew check`（含 integrationTest+verifyFlywayMigrations）。中文注释。NEVER `git add -A`（按显式路径）。commit 页脚 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。
- **禁止 CI 外包**：所有验证本地跑。

## 权威事实（实证，实现时引用，勿重新假设）

- 目标文件 `src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java`（1669 行）；`evaluateSource` L422-699；同步工作在 `Uni.item(()->{ try{...}catch{...} })` L485-692。
- 真实事件顺序（byte-parity 基准）：`executor(L531-543) → trace drain finally(L544-548) → metrics(L550-558) → recordLoanDecision(L560-563) → audit publish(L565-580) → LOG → DecisionTrace 组装(L588-599) → recordApiCall(success) → EvaluationResponse(L606-610) → ReplayMetadata compute(L612-639)`。
- replay 子块行号：buildVocabularyIndex 调 L489-490；buildAliasSet 调 L493-494；aliasesTrusted 派生 L509-510（`InternalCallerFilter.isHmacVerified(jaxrsCtx)`）；effectiveReplayCapture L517；captureTraceSteps L519；TraceAccess arm/else-drain L520-527；executor 调用 L531-543（`dynamicCnlExecutor.executeWithTenantContext(tenantId, source, context, functionName, locale, vocabIndex, legacyEvaluateSentinel, aliasSet, aliasesTrusted)`）；trace drain finally L544-548；DecisionTrace 组装 L588-599；ReplayMetadata compute + 异常降级 L612-639（`traceReplayable` 参数 = `traceDrainResult == null || traceDrainResult.replayable()` L626，load-bearing 逐字保留）。
- private helpers（移入 core，全 pure/仅 LOG）：`buildVocabularyIndex`(L1525，`VocabularyLoader.loadFromMap`→`IdentifierIndex.build`，null/invalid→null+warn)、`buildAliasSet`(L1505，纯，null/empty→null)、`toTraceSteps`(L1540 static)、`toChildTraceSteps`(L1561 static)、`traceSequence`(L1574 static)、`traceExpression`(L1584 static)——后四个 static 自包含。
- 注入 bean 分类：`dynamicCnlExecutor`(L173，→adapter 委托)、`toolchainIdentityProvider`(L182，→adapter 惰性传 String)、`legacyEvaluateSentinel`(L185 config，→boolean 传入)、`policyMetrics`/`businessMetrics`/`jaxrsCtx`/`auditPublisher`(→留 resource)。
- `DynamicCnlExecutor.ExecutionResult` record 字段（`DynamicCnlExecutor.java:86`）：`Object result, String moduleName, String functionName, long executionTimeMs`。
- `EvaluationResponse.success(result, execTimeMs, trace?decisionTrace:null, functionName)`（4-arg，L61）+ `withReplayMetadata(rm)`（L87）——adapter 保持原样。
- 现有测试：`ReplayMetadataTest`（`src/test/java/io/aster/policy/replay/ReplayMetadataTest.java`，32 `@Test`，**无 @QuarkusTest**，可整体移入 core）；`DynamicCnlExecutor{EntryPoint,Sandbox,Cache}Test`（留 aster-api，executor 未移）；`PublicApiContractTest`（golden `src/test/resources/api/public-api-contract.golden`，守 `/evaluate-source` 存在）；`InternalCallerFilterHmacIT`/`QuotaChainIT`（integration）。
- `settings.gradle`：`rootProject.name='aster-api'`，**无本地 include**（首个子模块）。加 `include 'aster-replay-core'` + 新 `aster-replay-core/build.gradle`；aster-api `build.gradle` 加 `implementation project(':aster-replay-core')`。
- 分支：当前在 `main`——Task 0 先建分支。

---

## File Structure

**新建**：
- `settings.gradle` 加 `include 'aster-replay-core'`（修改）
- `aster-replay-core/build.gradle`（新，依赖 asterLibs.core/truffle + Jackson，**无 quarkus**）
- `aster-replay-core/src/main/java/io/aster/replay/core/ReplayExecutor.java`（接口）
- `aster-replay-core/src/main/java/io/aster/replay/core/ReplayExecutorResult.java`（core DTO）
- `aster-replay-core/src/main/java/io/aster/replay/core/ReplayExecutionRequest.java`（core DTO）
- `aster-replay-core/src/main/java/io/aster/replay/core/ExecutionPhaseResult.java`（core DTO）
- `aster-replay-core/src/main/java/io/aster/replay/core/ReplayExecutionCore.java`（三阶段编排）
- `aster-replay-core/src/main/java/io/aster/policy/replay/ReplayMetadata.java`（**移入**，原 FQCN）
- `aster-replay-core/src/main/java/io/aster/policy/api/model/DecisionTrace.java`（**移入**，原 FQCN）
- trace glue（`toTraceSteps` 等 4 个 static + `buildVocabularyIndex`/`buildAliasSet`）移入 core（内部类或 helper）
- `aster-replay-core/src/test/java/io/aster/policy/replay/ReplayMetadataTest.java`（**移入**）

**修改**：
- `aster-api/build.gradle`（加 `implementation project(':aster-replay-core')`）
- `PolicyEvaluationResource.java`（evaluateSource 改三阶段调用 + 删移出的 private helpers）
- 新增 aster-api `ReplayExecutorAdapter`（委托 `DynamicCnlExecutor`，映射 `ExecutionResult`→`ReplayExecutorResult`）

**新增测试**（字符化，aster-api 侧，byte-parity 门）：
- `PolicyEvaluationReplayOrderingTest`（钉事件顺序 + 异常优先级 + ThreadLocal 清理 + metadata 降级）

---

## Task 0: 建分支 + 空 aster-replay-core 子模块骨架

**Files:**
- Create branch
- Modify: `settings.gradle`
- Create: `aster-replay-core/build.gradle`

**Interfaces:**
- Produces: 可编译的空子模块 `aster-replay-core`，aster-api 依赖它。后续 Task 往里加类。

- [ ] **Step 1: 建分支**

```bash
cd /Users/rpang/IdeaProjects/aster-api
git checkout -b p0a-s2-1a-0-replay-core-extraction
```

- [ ] **Step 2: settings.gradle 加子模块**

在 `settings.gradle` 的 `rootProject.name = 'aster-api'` 行**之后**加：

```groovy
// S2-1a-0：replay 执行编排共享模块（aster-api 与 β runner 复用同一份保 byte-parity）
include 'aster-replay-core'
```

- [ ] **Step 3: 写 aster-replay-core/build.gradle**

Create `aster-replay-core/build.gradle`：

```groovy
// aster-replay-core：从 aster-api 抽出的 replay 执行编排（无 Quarkus/DB）。
// 依赖 aster-lang core/truffle + Jackson；被 aster-api 与后续 β runner 共同依赖。
plugins {
    id 'java-library'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    api asterLibs.core        // CanonicalJson/IdentifierIndex/SemanticTokenKind/VocabularyLoader
    api asterLibs.truffle     // TraceAccess/TraceCollector
    implementation asterLibs.runtime
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test {
    useJUnitPlatform()
}
```

★实现时核对 aster-api `build.gradle` 里 asterLibs.core/truffle/runtime 的确切引用名 + Jackson 坐标（用同一 catalog/版本），保证 core 模块依赖版本与 aster-api 一致。

- [ ] **Step 4: aster-api build.gradle 依赖子模块**

在 `aster-api/build.gradle` 的 `dependencies {` 块内加（紧邻 asterLibs 依赖处）：

```groovy
    implementation project(':aster-replay-core')
```

- [ ] **Step 5: 验证空骨架编译**

Run:
```bash
cd /Users/rpang/IdeaProjects/aster-api
./gradlew :aster-replay-core:compileJava aster-api:compileJava 2>&1 | tail -15 || ./gradlew compileJava 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL（空子模块 + aster-api 编译通过）。★若 asterLibs 引用名不对会报依赖解析失败——按 aster-api build.gradle 实际名修正。

- [ ] **Step 6: Commit**

```bash
git add settings.gradle aster-replay-core/build.gradle build.gradle
git commit -m "$(cat <<'EOF'
feat(s2-1a-0): 新增 aster-replay-core 空子模块骨架

首个本地 Gradle 子模块。aster-api 依赖它, 后续把 replay orchestration
移入。无 Quarkus/DB。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 1: 移入 ReplayMetadata + DecisionTrace + ReplayMetadataTest（保原 FQCN）

**Files:**
- Move: `ReplayMetadata.java`（aster-api → core，原包 `io.aster.policy.replay`）
- Move: `DecisionTrace.java`（aster-api → core，原包 `io.aster.policy.api.model`）
- Move: `ReplayMetadataTest.java`（aster-api → core）

**Interfaces:**
- Consumes: Task 0 的 core 模块。
- Produces: core 拥有 `io.aster.policy.replay.ReplayMetadata` + `io.aster.policy.api.model.DecisionTrace`（FQCN 不变）；aster-api 通过 `project(':aster-replay-core')` 依赖可见。`ReplayMetadataTest` 在 core 跑。

**★为什么先移这两个**：它们本就 pure-JVM（只依赖 aster-lang-core `CanonicalJson` + Jackson + JDK），无 Quarkus/aster-api 耦合，是最干净的一刀；且 `ReplayMetadataTest`（32 测，无 @QuarkusTest）是最强 byte-parity 门，移进 core 立即可跑证明 compute 逻辑未变。

- [ ] **Step 1: git mv 两个源文件 + 测试到 core（保包路径）**

```bash
cd /Users/rpang/IdeaProjects/aster-api
mkdir -p aster-replay-core/src/main/java/io/aster/policy/replay
mkdir -p aster-replay-core/src/main/java/io/aster/policy/api/model
mkdir -p aster-replay-core/src/test/java/io/aster/policy/replay
git mv src/main/java/io/aster/policy/replay/ReplayMetadata.java aster-replay-core/src/main/java/io/aster/policy/replay/ReplayMetadata.java
git mv src/main/java/io/aster/policy/api/model/DecisionTrace.java aster-replay-core/src/main/java/io/aster/policy/api/model/DecisionTrace.java
git mv src/test/java/io/aster/policy/replay/ReplayMetadataTest.java aster-replay-core/src/test/java/io/aster/policy/replay/ReplayMetadataTest.java
```

- [ ] **Step 2: 检查移入类的 import 是否全在 core 可解析**

Run:
```bash
cd /Users/rpang/IdeaProjects/aster-api
grep -hE '^import ' aster-replay-core/src/main/java/io/aster/policy/replay/ReplayMetadata.java aster-replay-core/src/main/java/io/aster/policy/api/model/DecisionTrace.java | grep -vE 'java\.|com.fasterxml|aster.core' | sort -u
```
Expected: 空（或只剩 core 已依赖的 aster-lang/Jackson）。★若出现 `io.aster.policy.*` 或 `io.aster.common.*` 等 aster-api 内部 import → 该依赖也须移入或该类还不能移（报告并停，重新评估切分）。

- [ ] **Step 3: 编译 core**

Run:
```bash
./gradlew :aster-replay-core:compileJava 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 在 core 跑 ReplayMetadataTest（byte-parity 门）**

Run:
```bash
./gradlew :aster-replay-core:test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL，32 测全绿（ReplayMetadata.compute 逻辑未变）。

- [ ] **Step 5: 编译 aster-api（确认它经依赖看到移走的类）**

Run:
```bash
./gradlew aster-api:compileJava 2>&1 | tail -15 || ./gradlew compileJava 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL——`PolicyEvaluationResource` 等仍 import `io.aster.policy.replay.ReplayMetadata`/`io.aster.policy.api.model.DecisionTrace`，现在经 `project(':aster-replay-core')` 解析（FQCN 不变故 import 不改）。

- [ ] **Step 6: 全 aster-api 单测（确认无回归）**

Run:
```bash
./gradlew test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL（含 `EvaluationResponse` 等消费者不受影响）。

- [ ] **Step 7: Commit**

```bash
git add aster-replay-core/src src/main/java/io/aster/policy/replay src/main/java/io/aster/policy/api/model src/test/java/io/aster/policy/replay
git commit -m "$(cat <<'EOF'
refactor(s2-1a-0): ReplayMetadata+DecisionTrace 移入 aster-replay-core

保原 FQCN(io.aster.policy.replay / io.aster.policy.api.model), 消费者 import
不改, 经 project 依赖解析。ReplayMetadataTest 32 测移入 core 全绿(compute
逻辑 byte-identical)。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: core DTO + ReplayExecutor 接口 + aster-api adapter（executor 不移）

**Files:**
- Create: `aster-replay-core/.../core/ReplayExecutorResult.java`
- Create: `aster-replay-core/.../core/ReplayExecutor.java`
- Create: aster-api `.../ReplayExecutorAdapter.java`（委托 `DynamicCnlExecutor`）

**Interfaces:**
- Consumes: Task 0/1。
- Produces: `io.aster.replay.core.ReplayExecutor`（接口）+ `ReplayExecutorResult`（core DTO，`result/moduleName/functionName/executionTimeMs`）。aster-api 的 `ReplayExecutorAdapter implements ReplayExecutor`，委托 `dynamicCnlExecutor.executeWithTenantContext(...)`，映射 `ExecutionResult`→`ReplayExecutorResult`。Task 4 的 core orchestration 依赖 `ReplayExecutor` 接口。

- [ ] **Step 1: 写 ReplayExecutorResult（core DTO）**

Create `aster-replay-core/src/main/java/io/aster/replay/core/ReplayExecutorResult.java`：

```java
package io.aster.replay.core;

/**
 * replay 执行结果的 core 拥有值类型。
 *
 * <p>★core public API 不得暴露 aster-api 的 {@code DynamicCnlExecutor.ExecutionResult}——
 * 否则 core 为声明签名会反依赖 aster-api，形成模块环。aster-api adapter 负责机械映射。
 */
public record ReplayExecutorResult(
        Object result,
        String moduleName,
        String functionName,
        long executionTimeMs) {
}
```

- [ ] **Step 2: 写 ReplayExecutor 接口**

Create `aster-replay-core/src/main/java/io/aster/replay/core/ReplayExecutor.java`：

```java
package io.aster.replay.core;

import java.util.List;
import java.util.Map;

/**
 * replay 执行的 core 抽象。aster-api 提供委托现有 DynamicCnlExecutor 的 adapter；
 * 后续 β runner 提供复用同一 executor implementation 的 wiring（不另写 parser/executor）。
 *
 * <p>★异常契约：原样透传——不捕获、不包装 executor 抛出的 runtime exception。
 * core 只在 finally 中 drain trace，随后抛出同一异常实例，由 aster-api resource
 * 的现有四类 catch + HTTP 映射处理。
 */
public interface ReplayExecutor {
    /**
     * ★收「已建」vocabIndex + aliasSet（与 DynamicCnlExecutor 一致）——raw→已建的
     * 构建逻辑归 core 的 ReplayExecutionCore（byte-parity 单一份），executor 收结果。
     *
     * @param aliasesTrusted 由调用方（adapter）从已验证的调用上下文派生，非业务输入。
     * @param vocabIndex     已建的 IdentifierIndex（core 用 buildVocabularyIndex 建）。
     * @param aliasSet       已建的 kind→phrases 映射（core 用 buildAliasSet 建）。
     */
    ReplayExecutorResult execute(
            String tenantId,
            String source,
            Object context,
            String functionName,
            String locale,
            aster.core.identifier.IdentifierIndex vocabIndex,
            boolean legacyEvaluateSentinel,
            Map<SemanticTokenKind, List<String>> aliasSet,
            boolean aliasesTrusted);
}
```

★`IdentifierIndex`/`SemanticTokenKind` 是 aster-lang-core 类型（core 已依赖 asterLibs.core），core 与 aster-api 都可见，不成环。核对 `DynamicCnlExecutor.executeWithTenantContext` 的确切参数类型/顺序对齐（研究给：`tenantId, source, context, functionName, locale, vocabIndex, legacyEvaluateSentinel, aliasSet, aliasesTrusted`）。`SemanticTokenKind` 的 import 实现时补。

- [ ] **Step 3: 写 aster-api ReplayExecutorAdapter**

Create `src/main/java/io/aster/policy/replay/ReplayExecutorAdapter.java`（aster-api 侧，可注入 `DynamicCnlExecutor`）：

```java
package io.aster.policy.replay;

import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.replay.core.ReplayExecutor;
import io.aster.replay.core.ReplayExecutorResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/**
 * ReplayExecutor 的 aster-api 实现：委托现有 DynamicCnlExecutor，把其
 * ExecutionResult 机械映射为 core 拥有的 ReplayExecutorResult。
 *
 * <p>★首刀 executor 本体不移——本 adapter 是 core 接口与 aster-api 现有
 * executor 之间的边界，避免 core 反依赖 DynamicCnlExecutor。
 */
@ApplicationScoped
public class ReplayExecutorAdapter implements ReplayExecutor {

    @Inject
    DynamicCnlExecutor dynamicCnlExecutor;

    // ★收「已建」vocabIndex/aliasSet（core 建好传入），adapter 只透传 + 映射结果。
    @Override
    public ReplayExecutorResult execute(
            String tenantId, String source, Object context,
            String functionName, String locale,
            aster.core.identifier.IdentifierIndex vocabIndex,
            boolean legacyEvaluateSentinel,
            Map<SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        DynamicCnlExecutor.ExecutionResult r = dynamicCnlExecutor.executeWithTenantContext(
                tenantId, source, context, functionName, locale,
                vocabIndex, legacyEvaluateSentinel, aliasSet, aliasesTrusted);
        return new ReplayExecutorResult(r.result(), r.moduleName(), r.functionName(), r.executionTimeMs());
    }
}
```

★**vocab/alias 构建归 core**（`ReplayExecutionCore.execute` 内用移入的 `buildVocabularyIndex`/`buildAliasSet` 建，byte-parity 单一份），**adapter 收已建结果只透传**。故本 adapter 骨架已完整（无占位），Task 4 不再回填 adapter 逻辑（只需 Task 4 的 core 建 index 后经 `ReplayExecutor.execute` 传入）。`SemanticTokenKind` import 实现时补。

- [ ] **Step 4: 编译 + 单测**

Run:
```bash
./gradlew :aster-replay-core:compileJava aster-api:compileJava test 2>&1 | tail -20 || ./gradlew compileJava test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL（接口 + DTO + adapter 骨架编译；现有行为未变，因 evaluateSource 尚未改调 adapter——Task 4 才切）。

- [ ] **Step 5: Commit**

```bash
git add aster-replay-core/src/main/java/io/aster/replay/core src/main/java/io/aster/policy/replay/ReplayExecutorAdapter.java
git commit -m "$(cat <<'EOF'
feat(s2-1a-0): ReplayExecutor 接口 + core DTO + aster-api adapter

core 拥有 ReplayExecutor/ReplayExecutorResult(不暴露 DynamicCnlExecutor 防
签名环)。aster-api ReplayExecutorAdapter 委托现有 executor 映射结果。executor
本体不移。vocab/alias 构建归属待 Task 4 编排层定案后回填。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 字符化测试（先钉现状行为，再重构——byte-parity 安全网）

**Files:**
- Create: `src/test/java/io/aster/policy/rest/PolicyEvaluationReplayOrderingTest.java`

**Interfaces:**
- Consumes: 现状 `PolicyEvaluationResource`（未重构）。
- Produces: 钉死现状事件顺序 + 异常优先级 + ThreadLocal 清理 + metadata 降级的字符化测试。**这些测试在重构前写、必须对现状绿**，重构后仍绿 = 行为不变的证明。

**★为什么先写测试**：这是 TDD 的「先钉现状」——字符化测试捕获**当前**行为，重构（Task 4）后必须仍绿。现有 `ReplayMetadataTest`/`PublicApiContractTest` 不覆盖「metrics 抛异常时不调 metadata」这类时序，必须新增。

- [ ] **Step 1: 写字符化测试（对现状绿）**

Create `src/test/java/io/aster/policy/rest/PolicyEvaluationReplayOrderingTest.java`——覆盖（用 `@QuarkusTest` + 注入 mock 或真实调用 evaluate-source，按 aster-api 现有 resource 测试模式，参考 `PolicyEvaluationResourceTrialIdentityTest`）：

```java
package io.aster.policy.rest;

// ★实现时按 aster-api 现有 @QuarkusTest resource 测试模式写（RestAssured 或注入）。
// 覆盖以下现状行为（重构前必须绿，重构后仍绿）：
//
// 1. 成功路径 + replayCapture=true(HMAC 已验)：响应含 replayMetadata，
//    canonicalOutputHash 非空，replayabilityStatus=REPLAYABLE；
//    响应原始 JSON bytes 与 golden 一致（★比 bytes 非只字段）。
// 2. trace=false & replayCapture=false：响应无 decisionTrace 无 replayMetadata；
//    历史 ThreadLocal 被清理（后续请求不带残留 trace）。
// 3. trace=false & replayCapture=true：只返回 replayMetadata，不返回 decisionTrace。
// 4. executor 抛 CnlParseException（畸形 source）：HTTP 400，响应无 replayMetadata
//    （executor 失败不产 metadata）；trace 已 drain（后续请求干净）。
// 5. metadata compute 异常降级：构造使 ReplayMetadata.compute 内部抛的输入，
//    验证响应 replayMetadata.replayabilityStatus=NON_REPLAYABLE +
//    reasons 含 "compute_threw:"（现状 L628-637 降级）。
//    ★若难以从外部构造，用 ReplayMetadataTest 已有的等价单测覆盖，此处标注。
//
// ★metrics/audit 抛异常时不调 metadata/toolchain：这属内部时序，
//   若无法从 @QuarkusTest 外部注入故障，则在 Task 4 重构时用 core 单元测试
//   （直接调三阶段 API + mock adapter 抛异常）覆盖——本 Step 标注为 Task 4 补。
```

★**诚实边界**：能从 REST 外部钉的（1-5）在此写；纯内部时序（metrics 抛异常跳过 metadata）留 Task 4 用 core 三阶段 API 单测覆盖（那时 API 已可分阶段调，可 mock adapter 抛异常验证 metadata 阶段未被调）。**本 Step 不假装覆盖了做不到的**。

- [ ] **Step 2: 对现状跑（必须绿）**

Run:
```bash
./gradlew test --tests 'io.aster.policy.rest.PolicyEvaluationReplayOrderingTest' 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL（钉的是现状，未重构，应绿）。★若某条对现状红 = 我对现状理解错，停下核对再改测试（不是改产品）。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/aster/policy/rest/PolicyEvaluationReplayOrderingTest.java
git commit -m "$(cat <<'EOF'
test(s2-1a-0): replay 事件顺序/异常/ThreadLocal 字符化测试(钉现状)

重构前钉死现状行为(响应原始 bytes/executor 异常不产 metadata/ThreadLocal
清理/metadata 降级), 重构后仍绿=byte-identical 证明。纯内部时序(metrics
抛异常跳 metadata)留 Task 4 core 单测。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 三阶段 ReplayExecutionCore + evaluateSource 改调（核心一刀）

**Files:**
- Create: `aster-replay-core/.../core/ReplayExecutionRequest.java`、`ExecutionPhaseResult.java`、`ReplayExecutionCore.java`
- Move: trace glue（`toTraceSteps`/`toChildTraceSteps`/`traceSequence`/`traceExpression`/`buildVocabularyIndex`/`buildAliasSet`）从 resource → core
- Modify: `PolicyEvaluationResource.evaluateSource`（改三阶段调用，删移出的 helpers）
- Modify: `ReplayExecutorAdapter`（回填 vocab/alias 归属）

**Interfaces:**
- Consumes: Task 2 的 `ReplayExecutor`/`ReplayExecutorResult`，Task 1 的 `ReplayMetadata`/`DecisionTrace`。
- Produces: `ReplayExecutionCore` 三阶段 API：
  - `ExecutionPhaseResult execute(ReplayExecutionRequest req, ReplayExecutor executor)` — 建 vocabIndex/aliasSet、arm trace、调 executor、finally drain，返回 `{execResult: ReplayExecutorResult, traceDrainResult}`。
  - `DecisionTrace buildDecisionTrace(ReplayExecutorResult execResult, TraceAccess.DrainResult drain, boolean captureTrace)` — L588-599 逻辑。
  - `ReplayMetadata computeReplayMetadata(String toolchainId, Object context, ReplayExecutorResult execResult, DecisionTrace trace, TraceAccess.DrainResult drain)` — L612-639 逻辑 + 异常降级。
- resource 按三阶段调用，阶段间原位保留 metrics/audit/error-mapping。

- [ ] **Step 1: 写 ReplayExecutionRequest + ExecutionPhaseResult（core DTO）**

Create `ReplayExecutionRequest.java`（raw vocabulary/aliasSet，aliasesTrusted 传入，**无 toolchainId**）：

```java
package io.aster.replay.core;

import java.util.List;
import java.util.Map;

public record ReplayExecutionRequest(
        String tenantId,
        String source,
        Object context,                       // 原始 request context，不转 positional
        String functionName,
        String locale,
        Map<String, Object> vocabulary,       // raw；core 建 IdentifierIndex
        Map<String, List<String>> aliasSet,   // raw；core 建 kind→phrases
        boolean legacyEvaluateSentinel,
        boolean aliasesTrusted,               // adapter 从已验上下文派生
        boolean trace,
        boolean effectiveReplayCapture) {
}
```

Create `ExecutionPhaseResult.java`：

```java
package io.aster.replay.core;

import io.aster.policy.truffle.TraceAccess;   // ★核对 TraceAccess 的真实 FQCN（aster-lang-truffle）

public record ExecutionPhaseResult(
        ReplayExecutorResult execResult,
        TraceAccess.DrainResult traceDrainResult) {   // null 当 !captureTraceSteps
}
```

★核对 `TraceAccess.DrainResult` 的真实包名（aster-lang-truffle，研究说 `TraceAccess`/`TraceCollector` 来自 truffle）——core 已依赖 truffle，可用。

- [ ] **Step 2: 写 ReplayExecutionCore 三阶段（移入 glue）**

Create `ReplayExecutionCore.java`——把 resource 的 `buildVocabularyIndex`(L1525)/`buildAliasSet`(L1505)/`toTraceSteps`+3 helper(L1540-1584) 逻辑**逐字移入**（保持行为），组成三阶段：

```java
package io.aster.replay.core;

// ★逐字移植 resource 的：
//   - buildVocabularyIndex(L1525): VocabularyLoader.loadFromMap → IdentifierIndex.build；null/invalid→null+warn
//   - buildAliasSet(L1505): kind-name→phrases EnumMap；skip unknown；null/empty→null
//   - toTraceSteps/toChildTraceSteps/traceSequence/traceExpression(L1540-1584): static 纯
//   - TraceAccess arm/drain(L520-548)、DecisionTrace 组装(L588-599)、ReplayMetadata compute+降级(L612-639)
//
// execute():
//   vocabIndex = buildVocabularyIndex(req.vocabulary())      // ★先 vocab
//   aliasMap   = buildAliasSet(req.aliasSet())               // ★后 alias（保顺序）
//   captureTraceSteps = req.trace() || req.effectiveReplayCapture()
//   if (captureTraceSteps) TraceAccess.armCurrentThread(TraceCollector.withDefaults());
//   else TraceAccess.drainCurrentThread();                   // 清历史 ThreadLocal
//   ReplayExecutorResult execResult;
//   TraceAccess.DrainResult drain = null;
//   try {
//     execResult = executor.execute(req.tenantId(), req.source(), req.context(),
//        req.functionName(), req.locale(), vocabIndex-or-raw, req.legacyEvaluateSentinel(),
//        aliasMap-or-raw, req.aliasesTrusted());   // ★executor 收已建 index：见下澄清
//   } finally {
//     if (captureTraceSteps) drain = TraceAccess.drainCurrentThread();
//   }
//   return new ExecutionPhaseResult(execResult, drain);
//   // ★executor 异常：不捕获，finally 已 drain，异常继续抛（原样透传）
//
// buildDecisionTrace(execResult, drain, captureTrace):
//   if (!captureTrace) return null;
//   steps = drain == null ? List.of() : toTraceSteps(drain.steps());
//   return new DecisionTrace(execResult.moduleName(), execResult.functionName(),
//       steps, execResult.result(), execResult.executionTimeMs());
//
// computeReplayMetadata(toolchainId, context, execResult, decisionTrace, drain):
//   try {
//     return ReplayMetadata.compute(toolchainId, context, execResult.result(),
//         decisionTrace, drain == null || drain.replayable());   // ★L626 表达式逐字
//   } catch (Exception rmEx) {
//     return new ReplayMetadata(toolchainId, ReplayMetadata.CANONICALIZATION_VERSION,
//         null, null, null, List.of(), ReplayMetadata.STATUS_NON_REPLAYABLE,
//         List.of("compute_threw: " + rmEx.getMessage()), null, null, null);  // ★L628-637 逐字
//   }
```

★**executor 收已建 index（Task 2 接口已定）**：`ReplayExecutionCore.execute` 内用移入的 `buildVocabularyIndex`/`buildAliasSet` 建 index/aliasMap，通过 `ReplayExecutor.execute`（Task 2 签名，收 `IdentifierIndex`/`Map<SemanticTokenKind,...>`）传给 adapter → adapter 透传给 `dynamicCnlExecutor.executeWithTenantContext`。core 拥有构建逻辑（byte-parity），executor 收已建 index（与生产 L489-494 一致）。adapter 无需改（Task 2 已定终态）。

- [ ] **Step 3: 改 evaluateSource 为三阶段调用**

`PolicyEvaluationResource.evaluateSource`：
- 删 L1505/L1525/L1540-1584 的 6 个 private helpers（已移 core）。
- L489-494 建 index/alias 的调用**移入 core execute**——resource 不再直接建。
- L509-510 `aliasesTrusted` 派生**留 resource**（耦合 jaxrsCtx）。
- L520-548（arm/executor/drain）→ `ExecutionPhaseResult phase = replayExecutionCore.execute(req, replayExecutorAdapter);`
- L550-580 metrics/loan/audit/log **原位不动**（用 `phase.execResult()`）。
- L588-599 → `DecisionTrace decisionTrace = replayExecutionCore.buildDecisionTrace(phase.execResult(), phase.traceDrainResult(), trace || effectiveReplayCapture);`
- L601-610 recordApiCall + EvaluationResponse **原位不动**。
- L612-639 → `if (effectiveReplayCapture) { ReplayMetadata rm = replayExecutionCore.computeReplayMetadata(toolchainIdentityProvider.currentToolchainId(), request.context(), phase.execResult(), decisionTrace, phase.traceDrainResult()); response = response.withReplayMetadata(rm); }`
- L642-691 四类 catch + HTTP 映射 **原位不动**（executor 异常经 core 原样透传上来）。
- 注入 `ReplayExecutionCore`（core 无 CDI，用 `new` 或 `@Produces`；★core 类可 `@ApplicationScoped` 吗？——core 无 Quarkus 依赖，故 aster-api 侧包一层 `@ApplicationScoped @Produces ReplayExecutionCore` 或直接 `new`，实现时按 aster-api CDI 惯例）。

- [ ] **Step 4: 编译**

Run:
```bash
./gradlew :aster-replay-core:compileJava aster-api:compileJava 2>&1 | tail -20 || ./gradlew compileJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL。★`grep -c 'private.*buildVocabularyIndex\|private.*buildAliasSet\|private.*toTraceSteps' src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java` 应为 0（已移走）。

- [ ] **Step 5: 字符化测试 + 全单测（byte-parity 门）**

Run:
```bash
./gradlew test 2>&1 | tail -25
```
Expected: BUILD SUCCESSFUL——Task 3 字符化测试**仍绿**（行为不变）+ `ReplayMetadataTest` 绿 + `PublicApiContractTest` golden 不变 + 全 aster-api 单测绿。★补 Task 3 遗留的内部时序 core 单测（在 core 测 `computeReplayMetadata` 只在 success 后调 + mock `ReplayExecutor` 抛异常验证 execute 阶段抛出不进 buildDecisionTrace）。

- [ ] **Step 6: 集成测试（HMAC/quota 路径不变）**

Run:
```bash
./gradlew integrationTest 2>&1 | tail -25
```
Expected: BUILD SUCCESSFUL——`InternalCallerFilterHmacIT`（驱动 aliasesTrusted/replayCapture）+ `QuotaChainIT` 绿（adapter 边界不改 HMAC/quota 行为）。

- [ ] **Step 7: Commit**

```bash
git add aster-replay-core/src/main/java/io/aster/replay/core src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java src/main/java/io/aster/policy/replay/ReplayExecutorAdapter.java
git commit -m "$(cat <<'EOF'
refactor(s2-1a-0): evaluateSource 改三阶段调 ReplayExecutionCore

replay orchestration(vocab/alias 建/trace arm-drain/DecisionTrace/ReplayMetadata
compute+降级)+6 glue 逐字移入 core 三阶段 API。resource 在阶段间原位保留
metrics/audit/error-mapping。executor 不移(adapter 委托), 异常原样透传保四类
HTTP 映射。字符化+ReplayMetadata+contract golden+集成全绿=byte-identical。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 全量验收 + 交叉审查（禁止自审）

**Files:** 无新增（汇总验证）。

- [ ] **Step 1: 全量本地验证**

Run:
```bash
cd /Users/rpang/IdeaProjects/aster-api
./gradlew check 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL（test + integrationTest + verifyFlywayMigrations）。★确认 `PublicApiContractTest` golden 未变（`/evaluate-source` 契约不变）。

- [ ] **Step 2: 确认 byte-identical 证据齐**

Run:
```bash
echo "=== ReplayMetadataTest 在 core ==="; ./gradlew :aster-replay-core:test 2>&1 | grep -iE 'test.*(pass|complet|success)|BUILD'
echo "=== 6 helper 已移出 resource ==="; grep -cE 'private.*(buildVocabularyIndex|buildAliasSet|toTraceSteps|toChildTraceSteps|traceSequence|traceExpression)' src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java
echo "=== core 不引用 aster-api executor 类型 ==="; grep -rE 'DynamicCnlExecutor|ModuleResolver|io\.aster\.policy\.parser' aster-replay-core/src/main/java/io/aster/replay/core/ && echo "LEAK!" || echo "clean (no aster-api leak in core public api)"
```
Expected: ReplayMetadataTest 绿；helper count = 0；core 无 aster-api executor 泄漏。

- [ ] **Step 3: Codex 交叉审查（禁止自审）**

把全部改动交 Codex 审。审查重点：三阶段调用是否保持生产事件顺序（metrics/audit 在 executor 与 metadata 之间）；executor 异常原样透传保四类 HTTP 映射；core 无签名环（ReplayExecutorResult core 拥有）；`traceReplayable` 表达式 + 降级逐字；vocab 先于 alias；toolchainId 惰性；ThreadLocal 清理；byte-identical（字符化+golden 证据）。决策规则：≥90 且「建议通过」→ 交用户；<80「退回」→ 修；80-89 仔细审。

- [ ] **Step 4: 交用户决策合入（PR）**

★合入前须用户确认（逐 PR 停·审·合）。PR 到 main（走 aster-api ruleset）。

---

## Self-Review（对照 spike §2c）

**Spec coverage**：
- 三阶段 API（非一次 execute）→ Task 4。✓
- 首刀不移 executor + ReplayExecutor 接口 + adapter → Task 2/4。✓
- core 拥有 DTO（ReplayExecutorResult 防签名环）→ Task 2。✓
- 异常原样透传保四类 HTTP → Task 4 + Global Constraints。✓
- raw vocabulary（core 建 index）/aliasesTrusted 传入/toolchainId 惰性 → Task 4 Step 2。✓
- 移入类保 FQCN → Task 1。✓
- 字符化测试门（事件顺序/异常/ThreadLocal/降级/原始 bytes）→ Task 3 + Task 4 Step 6。✓
- TraceAccess 铁律（同线程/finally drain/不新增异步）→ Task 4 Step 2。✓
- ModuleGraphResolver 延后 → 不在本 plan（Global Constraints 声明）。✓

**Placeholder scan**：Task 2 Step 3 的 adapter vocab/alias 构建标注「依赖 Task 4」——Task 4 Step 2/3 明确定案（core 建 index，adapter 收已建 index，修正 ReplayExecutor 签名）。非交付占位（有明确回填指令 + 编译/测试验证）。★实现时 Task 2 与 Task 4 的 `ReplayExecutor` 签名必须一致（收 `IdentifierIndex`/`Map<SemanticTokenKind,...>`）——Task 4 Step 2 的澄清是权威。

**Type consistency**：`ReplayExecutorResult`（4 字段）Task 2 定义、Task 4 消费一致；`ReplayExecutor.execute` 签名 Task 2 初版收 raw、**Task 4 Step 2 修正为收已建 index**（权威，因 executor 收 index）——实现时以 Task 4 为准，Task 2 Step 2 的接口按此写。`ExecutionPhaseResult{execResult, traceDrainResult}` Task 4 定义、resource 消费一致。`TraceAccess.DrainResult` FQCN 实现时核对（aster-lang-truffle）。
