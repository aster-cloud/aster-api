# S2-1a-2 Runner MVP — Slice 1（A+B+Task 0）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 aster-api 的 `:replay` 模块加一个 standalone JVM runner（`main()` + `application` distribution 打包 + 最小 arm64 stock-JRE 镜像），并用 podman 真跑一次证明 **stock Zulu JRE 25 + Truffle 解释器能跑 + 产 byte-identical ReplayMetadata**（Task 0 风险门）。

**Architecture:** runner 是 `:replay` 之上的新 Gradle module `aster-replay-runner`，用 `application` plugin 产启动脚本 + `lib/*.jar` install 目录（**非 fat-jar**，locale 四 jar 物理分开保 SPI 零合并）。runner `main()` 读 JSON 请求 → `ReplayExecutionCore` 三阶段 → 向 stdout 输出结果 envelope。镜像基于 stock `azul/zulu-openjdk-alpine:25-jre`（无 Quarkus/PG/Redis）。

**Tech Stack:** Java 25 (toolchain), Gradle `application` plugin, GraalVM Polyglot/Truffle（解释器模式，`engine.WarnInterpreterOnly=false` 已在 `:replay` 内设），Jackson，podman（arm64 容器验证）。

## Global Constraints

- **本 slice 只做 A+B+Task 0**（spec 序列：A+B 最小切片 → Task 0 风险门 → C–F 另出计划）。Task 0 **阻塞 C–F**，若失败回头改 A/B。
- **打包 = `application` plugin（非 fat-jar/uber-jar）**：产启动脚本 + `lib/*.jar`；locale 四 jar 物理分开在 `lib/`，SPI 零合并风险。
- **★locale artifacts 必须提升为真 `runtimeOnly`**（en/zh/de/hi）：在 `:replay` 里它们是 `testRuntimeOnly`，runner module 必须显式加为 `runtimeOnly`，否则非英文 replay 静默分叉（byte-parity 头号陷阱）。坐标：`cloud.aster-lang:aster-lang-locales-{en,zh,de}` + `cloud.aster-lang:aster-lang-hi`（经 catalog 访问器 `asterLibs.en/zh/de/hi`）。
- **runner 是结果 envelope 唯一生产方**：向 **stdout** 输出。成功=`{outcome:"SUCCESS", replayMetadata:{...}}`（exit 0）；错误=`{outcome:"ERROR", errorCode, message, phase}`（exit≠0）。★错误**不进 ReplayMetadata**。envelope 是 stdout **最后一行完整 JSON**，前置 log 走 **stderr**。
- **import-free fail-closed**：runner 用 **`new DynamicCnlExecutor(null, true)`**（★**modulesEnabled=true + null resolver**，非 no-arg）。理由（`DynamicCnlExecutor.java:443-447` 实证）：`if (!imports.isEmpty() && modulesEnabled)` → 若 `modulesEnabled=false`（no-arg），import **被静默忽略非拒绝**（错误！）；须 `modulesEnabled=true`，则遇 import 走到 444 行 `if (moduleResolver == null) throw ModuleResolutionException`——**这才是真 fail-closed**。policy 用 `import` → 抛 `ModuleResolutionException` → 错误 envelope（errorCode=MODULE），exit≠0。
- **toolchainId 归一（byte-parity 判定关键）**：runner 与 aster-api build 标识天然不同（`build=` 字段来自 `aster.runtime.build` env）。parity 比对**排除 `toolchainId`**，只比 `canonicalInputHash + canonicalOutputHash + canonicalizationVersion + replayabilityStatus + traceHash` 逐字节一致。toolchainId 单独记录供诊断。
- **异常原样透传**：`ReplayExecutor` 契约不 wrap 异常；runner `main()` 顶层捕获 → 映射错误 envelope。
- **中文注释**（描述意图/约束/使用方式），遵循现有代码风格。
- **arm64 铁律**：Dockerfile 须真 arm64（承 `Dockerfile.jvm` 的 arch 断言，防 QEMU 误标 amd64→CrashLoop）。
- **无 CI 外包**：所有验证本地可复现（gradle + podman）。

## File Structure

- `settings.gradle`（aster-api 根）：加 `include 'aster-replay-runner'`。
- `aster-replay-runner/build.gradle`：新 module，`application` plugin + `mainClass`，`implementation project(':replay')` + locale runtimeOnly + graalvm/truffle runtime deps。
- `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java`：`main()` 入口，读 stdin JSON → 三阶段 → stdout envelope。
- `aster-replay-runner/src/main/java/io/aster/replay/runner/StandaloneReplayExecutor.java`：非 CDI `ReplayExecutor` 实现（仿 `ReplayExecutorAdapter`，用 `new DynamicCnlExecutor(null, true)`——modulesEnabled=true+null resolver=import fail-closed）。
- `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerRequest.java`：请求 DTO（schema ②）+ Jackson 反序列化。
- `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerEnvelope.java`：结果 envelope（③/④）+ Jackson 序列化。
- `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerToolchainId.java`：toolchainId 复现（仿 `ToolchainIdentityProvider`，4 常量 + 1 env）。
- `aster-replay-runner/src/main/java/io/aster/replay/runner/LocaleAssertion.java`：4 locale 启动断言 fail-closed（Task 5b）。
- `aster-replay-runner/src/test/java/io/aster/replay/runner/*Test.java`：单测（请求解析、import-free fail-closed、envelope、locale 断言、成功/错误路径）。
- `aster-replay-runner/scripts/gen-expected.sh` + `src/test/java/io/aster/replay/parity/GenExpectedCorpusTest.java`：经 aster-api evaluateSource 产权威 expected.json（Task 7）。
- `aster-replay-runner/Dockerfile`：最小 arm64 stock-JRE 镜像（COPY application `lib/` + 启动脚本 ENTRYPOINT）。
- `aster-replay-runner/src/test/resources/parity-corpus/`：固定 corpus（import-free 子集，四 locale）+ 期望 ReplayMetadata（toolchainId 排除后）。

---

### Task 1: `aster-replay-runner` module 骨架 + 空 `main()`

**Files:**
- Modify: `settings.gradle`（加 include）
- Create: `aster-replay-runner/build.gradle`
- Create: `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java`
- Test: `aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerMainSmokeTest.java`

**Interfaces:**
- Consumes: `:replay` module（`io.aster.replay.core.ReplayExecutionCore` 等），catalog `asterLibs.en/zh/de/hi`。
- Produces: 可运行 module `:aster-replay-runner`，`mainClass = 'io.aster.replay.runner.RunnerMain'`。

- [ ] **Step 1: Write the failing test**

```java
// aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerMainSmokeTest.java
package io.aster.replay.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 骨架冒烟：类可加载，mainClass 存在。 */
class RunnerMainSmokeTest {
    @Test
    void mainClassLoads() throws Exception {
        Class<?> c = Class.forName("io.aster.replay.runner.RunnerMain");
        assertNotNull(c.getDeclaredMethod("main", String[].class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerMainSmokeTest"`
Expected: FAIL — module/项目不存在（`Project ':aster-replay-runner' not found` 或编译失败）。

- [ ] **Step 3: 加 module include + build.gradle**

`settings.gradle` 在 `include 'replay'` 后加一行：
```groovy
// S2-1a-2：standalone β runner（复用 :replay 的 executor，application distribution 打包）
include 'aster-replay-runner'
```

Create `aster-replay-runner/build.gradle`（版本对齐 `:replay/build.gradle` 实测值——graal-sdk 23.1.2 是 quarkus-bom 约束胜出值；其余 25.0.3）：
```groovy
// aster-replay-runner：standalone β runner。复用 :replay 的 ReplayExecutionCore+DynamicCnlExecutor，
// 用 application plugin 产启动脚本 + lib/*.jar（非 fat-jar，locale 四 jar 分开保 SPI 零合并）。
plugins {
    id 'java'
    id 'application'
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

application {
    mainClass = 'io.aster.replay.runner.RunnerMain'
}

dependencies {
    implementation project(':replay')   // ReplayExecutionCore / DynamicCnlExecutor / records
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.22.0'

    // ★locale SPI：:replay 里是 testRuntimeOnly（不在主运行时 classpath）。runner 必须
    // 提升为真 runtimeOnly，否则非英文 replay 静默分叉（byte-parity 头号陷阱）。四 jar 物理
    // 分开在 application lib/，各自 META-INF/services 不撞名，ServiceLoader 自然聚合。
    runtimeOnly asterLibs.en
    runtimeOnly asterLibs.zh
    runtimeOnly asterLibs.de
    runtimeOnly asterLibs.hi

    // GraalVM/Truffle 运行时（:replay 已 api/implementation 传递 core/truffle；此处补 runner 自身
    // 运行所需 runtime artifacts，与 :replay/build.gradle 版本对齐）。
    runtimeOnly 'org.graalvm.truffle:truffle-runtime:25.0.3'
    runtimeOnly 'org.graalvm.truffle:truffle-compiler:25.0.3'
    runtimeOnly 'org.graalvm.compiler:compiler:25.0.3'

    testImplementation 'org.junit.jupiter:junit-jupiter:6.1.1'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: 写最小 `RunnerMain`**

Create `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java`：
```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :aster-replay-runner:compileJava :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerMainSmokeTest"`
Expected: PASS（BUILD SUCCESSFUL）。

- [ ] **Step 6: Commit**

```bash
git add settings.gradle aster-replay-runner/build.gradle aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerMainSmokeTest.java
git commit -m "feat(s2-1a-2): aster-replay-runner module 骨架 + application 打包"
```

---

### Task 2: 请求 DTO（schema ②）+ Jackson 反序列化

**Files:**
- Create: `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerRequest.java`
- Test: `aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerRequestTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`。
- Produces: `RunnerRequest` record，可从 schema ② JSON 反序列化，转成 `ReplayExecutionRequest` 的字段。

- [ ] **Step 1: Write the failing test**

```java
// aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerRequestTest.java
package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RunnerRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesSchema2Json() throws Exception {
        String json = """
            {"tenantId":"t1","source":"Module m.\\nRule r given x: Return x.",
             "input":{"x":1},"locale":"en","functionName":"r",
             "aliasSet":{"RULE":["规则"]}}
            """;
        RunnerRequest req = mapper.readValue(json, RunnerRequest.class);
        assertEquals("t1", req.tenantId());
        assertEquals("en", req.locale());
        assertEquals("r", req.functionName());
        assertEquals(List.of("规则"), req.aliasSet().get("RULE"));
        assertNotNull(req.input());
    }

    @Test
    void nullAliasSetAllowed() throws Exception {
        String json = """
            {"tenantId":"t1","source":"x","input":{},"locale":"en",
             "functionName":"r","aliasSet":null}
            """;
        RunnerRequest req = mapper.readValue(json, RunnerRequest.class);
        assertNull(req.aliasSet());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerRequestTest"`
Expected: FAIL — `RunnerRequest` 不存在（编译失败）。

- [ ] **Step 3: 写 `RunnerRequest`**

Create `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerRequest.java`：
```java
package io.aster.replay.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * runner 请求（spec schema ②）。字段与 launcher 透传的执行元组对应；
 * aliasSet 是 raw（未建 index），index/aliasSet 在 :replay 内建以保 byte-parity 单源。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunnerRequest(
        String tenantId,
        String source,
        Object input,
        String locale,
        String functionName,
        Map<String, List<String>> aliasSet
) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerRequestTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerRequest.java aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerRequestTest.java
git commit -m "feat(s2-1a-2): runner 请求 DTO（schema ②）+ Jackson 反序列化"
```

---

### Task 3: 结果 envelope（③/④）+ Jackson 序列化

**Files:**
- Create: `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerEnvelope.java`
- Test: `aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerEnvelopeTest.java`

**Interfaces:**
- Consumes: `io.aster.policy.replay.ReplayMetadata`（来自 `:replay`）。
- Produces: `RunnerEnvelope`——成功工厂 `success(ReplayMetadata)` / 错误工厂 `error(code, msg, phase)`，序列化成 ③/④。

- [ ] **Step 1: Write the failing test**

```java
// aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerEnvelopeTest.java
package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunnerEnvelopeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void errorEnvelopeHasNoReplayMetadata() throws Exception {
        RunnerEnvelope env = RunnerEnvelope.error("PARSE", "boom", "parse");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(env));
        assertEquals("ERROR", node.get("outcome").asText());
        assertEquals("PARSE", node.get("errorCode").asText());
        assertEquals("parse", node.get("phase").asText());
        // ★错误 envelope 绝不含 replayMetadata（成功与失败不共 schema）
        assertFalse(node.has("replayMetadata"));
    }

    @Test
    void successEnvelopeOutcomeIsSuccess() throws Exception {
        // ★用 ReplayMetadata 真实 11 字段 canonical 构造（ReplayMetadata.java:58-74 实证），
        //   不手编不存在的字段/工厂。M2 后 3 字段（canonicalInput/Output/Trace）传 null。
        io.aster.policy.replay.ReplayMetadata rm = new io.aster.policy.replay.ReplayMetadata(
            "abi=V1;core=dev;validator=dev;build=test",   // runtimeToolchainId
            io.aster.policy.replay.ReplayMetadata.CANONICALIZATION_VERSION,
            "inHash", "outHash", "traceHash",             // canonicalInput/Output/traceHash
            java.util.List.of(),                          // reasonCodes
            "REPLAYABLE",                                 // replayabilityStatus
            java.util.List.of(),                          // replayabilityReasons
            null, null, null);                            // M2: canonicalInput/Output/Trace
        RunnerEnvelope env = RunnerEnvelope.success(rm);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(env));
        assertEquals("SUCCESS", node.get("outcome").asText());
        assertTrue(node.has("replayMetadata"));
        assertFalse(node.has("errorCode"));
    }
}
```
★实现者注：`ReplayMetadata` 是 11 字段 public record（`ReplayMetadata.java:58-74`）；上面按真实字段顺序构造。若字段顺序/名有微调用编译 oracle 校准（`:aster-replay-runner:test` 绿）。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerEnvelopeTest"`
Expected: FAIL — `RunnerEnvelope` 不存在。

- [ ] **Step 3: 写 `RunnerEnvelope`**

Create `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerEnvelope.java`（`@JsonInclude(NON_NULL)` 保错误 envelope 不渲染 null 的 replayMetadata）：
```java
package io.aster.replay.runner;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.aster.policy.replay.ReplayMetadata;

/**
 * runner 结果 envelope（spec ③/④）。runner 是唯一生产方，向 stdout 输出。
 * ★错误不进 ReplayMetadata——成功承 replayMetadata，错误承 errorCode/message/phase，
 * 二者不共 schema（NON_NULL 保错误 envelope 不含 replayMetadata 字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerEnvelope(
        String outcome,           // "SUCCESS" | "ERROR"
        ReplayMetadata replayMetadata,   // 仅成功
        String errorCode,         // 仅错误：PARSE/EXECUTION/MODULE/INTERNAL
        String message,           // 仅错误
        String phase              // 仅错误：parse|execute|trace|metadata
) {
    public static RunnerEnvelope success(ReplayMetadata rm) {
        return new RunnerEnvelope("SUCCESS", rm, null, null, null);
    }
    public static RunnerEnvelope error(String errorCode, String message, String phase) {
        return new RunnerEnvelope("ERROR", null, errorCode, message, phase);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerEnvelopeTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerEnvelope.java aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerEnvelopeTest.java
git commit -m "feat(s2-1a-2): 结果 envelope（成功③/错误④）+ 独立 error envelope"
```

---

### Task 4: 非 CDI `StandaloneReplayExecutor`（import-free）

**Files:**
- Create: `aster-replay-runner/src/main/java/io/aster/replay/runner/StandaloneReplayExecutor.java`
- Test: `aster-replay-runner/src/test/java/io/aster/replay/runner/StandaloneReplayExecutorTest.java`

**Interfaces:**
- Consumes: `io.aster.replay.core.ReplayExecutor`（接口）、`io.aster.policy.parser.DynamicCnlExecutor`（`(ModuleGraphResolver, boolean)` 构造，用 `(null, true)`）、`io.aster.policy.module.ModuleResolutionException`。
- Produces: `StandaloneReplayExecutor implements ReplayExecutor`——非 CDI，构造 `new DynamicCnlExecutor(null, true)`（null resolver + modulesEnabled=true → import fail-closed 抛 ModuleResolutionException）。

- [ ] **Step 1: Write the failing test**

```java
// aster-replay-runner/src/test/java/io/aster/replay/runner/StandaloneReplayExecutorTest.java
package io.aster.replay.runner;

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
        // 跨模块 import → modulesEnabled=true + null resolver → ModuleResolutionException（原样透传）。
        assertThrows(io.aster.policy.module.ModuleResolutionException.class, () ->
            exec.execute("tenant-1",
                "Module probe.\nImport other.\nRule main given x as Int, produce Int:\n  Return x.",
                Map.of("x", 1), "main", "en-US", null, true, null, false));
    }
}
```
★实现者注：CNL `import` 的确切语法（`Import other.` vs 别的形态）用 `:replay`/aster-api 现有 import 测试语料校准（grep `Import ` in test fixtures）。若 `probe`/`main`/`x as Int` 语法在真编译下不符，用编译+运行 oracle 调到能过——核心不变量是「import-free 成功 + import 抛 ModuleResolutionException」。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.StandaloneReplayExecutorTest"`
Expected: FAIL — `StandaloneReplayExecutor` 不存在。

- [ ] **Step 3: 写 `StandaloneReplayExecutor`**

Create `aster-replay-runner/src/main/java/io/aster/replay/runner/StandaloneReplayExecutor.java`（仿 `ReplayExecutorAdapter` 但去 CDI）：
```java
package io.aster.replay.runner;

import aster.core.lexicon.SemanticTokenKind;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.replay.core.ReplayExecutor;
import io.aster.replay.core.ReplayExecutorResult;
import java.util.List;
import java.util.Map;

/**
 * ReplayExecutor 的 standalone 实现（非 CDI）。仿 aster-api ReplayExecutorAdapter，
 * 但用 new DynamicCnlExecutor(null, true)（★modulesEnabled=true + null resolver）→
 * import-free fail-closed：policy 用跨模块 import 时，DynamicCnlExecutor.java:444 因
 * moduleResolver==null 抛 ModuleResolutionException（异常原样透传，runner 顶层映射 MODULE 错误）。
 * ★不用 no-arg——no-arg 是 (null,false)，modulesEnabled=false 会让 import 被静默忽略非拒绝。
 */
public final class StandaloneReplayExecutor implements ReplayExecutor {

    // (null, true)：null ModuleGraphResolver + modulesEnabled=true → import 触发 fail-closed 抛异常。
    private final DynamicCnlExecutor executor = new DynamicCnlExecutor(null, true);

    @Override
    public ReplayExecutorResult execute(
            String tenantId, String source, Object context,
            String functionName, String locale,
            aster.core.identifier.IdentifierIndex vocabIndex,
            boolean legacyEvaluateSentinel,
            Map<SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        DynamicCnlExecutor.ExecutionResult r = executor.executeWithTenantContext(
                tenantId, source, context, functionName, locale,
                vocabIndex, legacyEvaluateSentinel, aliasSet, aliasesTrusted);
        return new ReplayExecutorResult(r.result(), r.moduleName(), r.functionName(), r.executionTimeMs());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.StandaloneReplayExecutorTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add aster-replay-runner/src/main/java/io/aster/replay/runner/StandaloneReplayExecutor.java aster-replay-runner/src/test/java/io/aster/replay/runner/StandaloneReplayExecutorTest.java
git commit -m "feat(s2-1a-2): 非 CDI StandaloneReplayExecutor（import-free fail-closed）"
```

---

### Task 5: toolchainId 复现 + `main()` 三阶段接线 + stdout envelope

**Files:**
- Create: `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerToolchainId.java`
- Modify: `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java`
- Test: `aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerMainIntegrationTest.java`

**Interfaces:**
- Consumes: `ReplayExecutionCore`（三阶段）、`RunnerRequest`、`RunnerEnvelope`、`StandaloneReplayExecutor`、`RunnerToolchainId`。
- Produces: `main()` 全链：stdin JSON → `ReplayExecutionRequest` → 三阶段 → stdout envelope；成功 exit 0 / 错误 exit≠0。

- [ ] **Step 1: Write the failing test**

```java
// aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerMainIntegrationTest.java
package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * main() 全链：喂 stdin JSON，捕获 stdout envelope（最后一行完整 JSON）。
 * 用抽出的 run(InputStream,PrintStream) 入口避免真调 System.exit。
 */
class RunnerMainIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successProducesReplayMetadataEnvelope() throws Exception {
        // ★CNL 语法用真实语料形态；locale "en-US"；trace/effectiveReplayCapture 在 toCoreRequest 内固定 true。
        String reqJson = """
            {"tenantId":"tenant-1","source":"Module probe.\\nRule main given x as Int, produce Int:\\n  Return x.",
             "input":{"x":1},"locale":"en-US","functionName":"main","aliasSet":null}
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RunnerMain.run(
            new ByteArrayInputStream(reqJson.getBytes()), new PrintStream(out));
        assertEquals(0, code);
        String lastLine = out.toString().strip().lines().reduce((a, b) -> b).orElseThrow();
        JsonNode env = mapper.readTree(lastLine);
        assertEquals("SUCCESS", env.get("outcome").asText());
        assertTrue(env.get("replayMetadata").has("canonicalInputHash"));
        // ★trace=true 路径 → traceHash 非 null（与生产 replayCapture 对齐，防 parity 分叉）。
        assertFalse(env.get("replayMetadata").get("traceHash").isNull());
    }

    @Test
    void importPolicyFailsClosed() throws Exception {
        // 跨模块 import → StandaloneReplayExecutor 的 (null,true) → ModuleResolutionException →
        // MODULE 错误 envelope，exit≠0，不含 replayMetadata。
        String reqJson = """
            {"tenantId":"tenant-1","source":"Module probe.\\nImport other.\\nRule main given x as Int, produce Int:\\n  Return x.",
             "input":{"x":1},"locale":"en-US","functionName":"main","aliasSet":null}
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RunnerMain.run(
            new ByteArrayInputStream(reqJson.getBytes()), new PrintStream(out));
        assertNotEquals(0, code);
        String lastLine = out.toString().strip().lines().reduce((a, b) -> b).orElseThrow();
        JsonNode env = mapper.readTree(lastLine);
        assertEquals("ERROR", env.get("outcome").asText());
        assertEquals("MODULE", env.get("errorCode").asText());
        assertFalse(env.has("replayMetadata"));
    }
}
```
★实现者注：三阶段接线参考 `PolicyEvaluationResource:522-603` 参考序列 + `ReplayExecutionCoreTest`（`:replay` 内）的既有用法——`ReplayExecutionCore.execute` 内部经 `buildVocabularyIndex`/`buildAliasSet` 从 raw vocabulary/aliasSet 建 index（vocabulary=null→null index 合法退化）。CNL `import` 语法用真实语料校准。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerMainIntegrationTest"`
Expected: FAIL — `RunnerMain.run(...)` 不存在。

- [ ] **Step 3: 写 `RunnerToolchainId`**

Create `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerToolchainId.java`（仿 `ToolchainIdentityProvider`，`build` 来自 env `ASTER_RUNTIME_BUILD` 默认 dev）：
```java
package io.aster.replay.runner;

/**
 * runner 侧 toolchainId 复现（仿 aster-api ToolchainIdentityProvider）。
 * 格式 abi=V1;core=<ver>;validator=<ver>;build=<runtimeBuild>。
 * ★build 字段来自 env ASTER_RUNTIME_BUILD——与 aster-api 镜像天然不同，正因如此
 * parity 比对必须排除 toolchainId（见 spec toolchainId 归一）。仅诊断，不进比对判定。
 */
public final class RunnerToolchainId {
    private RunnerToolchainId() {}

    public static String current() {
        String build = System.getenv().getOrDefault("ASTER_RUNTIME_BUILD", "dev");
        return "abi=" + aster.core.lexicon.LexiconAbiVersion.V1.version
            + ";core=" + coreEngineVersion()
            + ";validator=" + io.aster.policy.parser.UserAliasValidator.VERSION
            + ";build=" + build;
    }

    private static String coreEngineVersion() {
        String v = aster.core.canonicalizer.Canonicalizer.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }
}
```

- [ ] **Step 4: 接线 `RunnerMain`（三阶段 + envelope + exit code）**

Modify `RunnerMain.java`——抽 `run(InputStream, PrintStream) → int` 便于测试，`main()` 调它再 `System.exit`。三阶段参考 `PolicyEvaluationResource:522-603`（`new ReplayExecutionCore()` 无注入）。异常按 phase 映射错误 envelope：
```java
package io.aster.replay.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.replay.core.*;

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
     * 三阶段执行。参考 aster-api PolicyEvaluationResource:522-603 的参考序列，
     * 但用非 CDI StandaloneReplayExecutor + RunnerToolchainId。
     * ★实现者：按 :replay 的 ReplayExecutionRequest/ReplayExecutionCore 实际签名接线
     *   （raw vocabulary/aliasSet → core 建 index）；异常按 phase 映射错误 envelope。
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
            var trace = core.buildDecisionTrace(
                phase.execResult(), phase.traceDrainResult(), /* captureTrace */ true);
            var rm = core.computeReplayMetadata(
                RunnerToolchainId.current(), /* context */ req.input(),
                phase.execResult(), trace, phase.traceDrainResult());
            return RunnerEnvelope.success(rm);
        } catch (Exception e) {
            return mapError(e);
        }
    }

    /**
     * RunnerRequest → ReplayExecutionRequest（11 字段，ReplayExecutionCoreTest:35 实证形态）。
     * ★effectiveReplayCapture=true + trace=true：runner 的职责就是复现生产 replayCapture 路径，
     *   须与生产捕获的 ReplayMetadata 对齐（含 traceHash）。aliasesTrusted=false（runner 无 HMAC
     *   上下文，MVP 无签名——别名不受信，与 parity corpus 的 import-free/无别名子集一致）。
     *   legacyEvaluateSentinel=false。vocabulary/aliasSet 传 raw（可 null）。
     */
    private static ReplayExecutionRequest toCoreRequest(RunnerRequest req) {
        return new ReplayExecutionRequest(
            req.tenantId(), req.source(), req.input(), req.functionName(), req.locale(),
            /* vocabulary */ null, req.aliasSet(),
            /* legacyEvaluateSentinel */ false, /* aliasesTrusted */ false,
            /* trace */ true, /* effectiveReplayCapture */ true);
    }

    /**
     * 异常 → 错误 envelope。对齐 aster-api 四类映射（PolicyEvaluationResource 的 catch 分类）：
     *   ModuleResolutionException→MODULE、CnlParseException/解析类→PARSE、其余执行异常→EXECUTION。
     * ★实现者按 :replay 实际异常 FQCN 校准 instanceof 分支（用编译 oracle）。
     */
    private static RunnerEnvelope mapError(Exception e) {
        if (e instanceof io.aster.policy.module.ModuleResolutionException) {
            return RunnerEnvelope.error("MODULE", String.valueOf(e.getMessage()), "execute");
        }
        // 解析类异常（CnlParseException 等）→ PARSE；实现者按真实 FQCN 补 instanceof。
        return RunnerEnvelope.error("EXECUTION", String.valueOf(e.getMessage()), "execute");
    }
}
```
★实现者注（load-bearing）：`toCoreRequest` 和 `mapError` 的确切实现须读 `:replay` 的 `ReplayExecutionRequest` record 真实字段 + aster-api `PolicyEvaluationResource` 的异常→HTTP 四类映射后写等价逻辑。**用编译 oracle**（`:aster-replay-runner:compileJava` 绿）+ 运行 oracle（本 Task 测试绿）校准，不硬猜签名。

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.RunnerMainIntegrationTest"`
Expected: PASS（两个用例：成功 envelope + import fail-closed）。

- [ ] **Step 6: Commit**

```bash
git add aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerToolchainId.java aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java aster-replay-runner/src/test/java/io/aster/replay/runner/RunnerMainIntegrationTest.java
git commit -m "feat(s2-1a-2): main() 三阶段接线 + toolchainId 复现 + stdout envelope"
```

---

### Task 5b: 四 locale 启动断言 + 负向测试（spec L142「locale 断言」——Codex 抓的缺失）

**Files:**
- Create: `aster-replay-runner/src/main/java/io/aster/replay/runner/LocaleAssertion.java`
- Modify: `aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java`（`run()` 起始调 `LocaleAssertion.assertAllPresent()`）
- Test: `aster-replay-runner/src/test/java/io/aster/replay/runner/LocaleAssertionTest.java`

**Interfaces:**
- Consumes: core `LexiconRegistry`（SPI discovery，`discoverPlugins()`）——★实现者读 `aster-lang-core` 的 `LexiconRegistry` 真实 API（如何查已注册 locale：`discoverPlugins()` 返回计数 / 查询某 locale 是否 registered），用编译 oracle 校准。
- Produces: `LocaleAssertion.assertAllPresent()`——4 locale（en/zh/de/hi）任一缺失即 throw（fail-closed），保 byte-parity 头号陷阱不静默发生。

- [ ] **Step 1: Write the failing test**

```java
// aster-replay-runner/src/test/java/io/aster/replay/runner/LocaleAssertionTest.java
package io.aster.replay.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ★正向：runner 的 4 locale artifact 在 testRuntime classpath（build.gradle 已加），
 *   assertAllPresent 不抛。★负向：本测试若发现缺 locale 应能定位（fail-closed 语义）。
 */
class LocaleAssertionTest {
    @Test
    void allFourLocalesPresent() {
        // 正向：4 locale SPI 都在 classpath（build.gradle runtimeOnly en/zh/de/hi + 测试同 classpath）→ 不抛。
        assertDoesNotThrow(LocaleAssertion::assertAllPresent);
    }

    @Test
    void checkPassesWhenAllPresent() {
        // 纯 seam 正向：present ⊇ REQUIRED → 不抛。
        assertDoesNotThrow(() -> LocaleAssertion.checkAgainst(
            java.util.Set.of("en", "zh", "de", "hi", "extra")));
    }

    @Test
    void checkThrowsWhenLocaleMissing() {
        // ★负向 fail-closed 证明（Codex 抓的缺失）：缺 hi → throw，异常消息含缺失 locale。
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            LocaleAssertion.checkAgainst(java.util.Set.of("en", "zh", "de")));  // 无 hi
        assertTrue(ex.getMessage().contains("hi"), "异常消息应列出缺失的 hi");
    }

    @Test
    void assertionListsRequiredLocales() {
        // 契约：REQUIRED 恰为 {en,zh,de,hi}（防未来漏 locale）。
        assertEquals(java.util.Set.of("en", "zh", "de", "hi"), LocaleAssertion.REQUIRED_LOCALES);
    }
}
```
★负向测试靠 `checkAgainst(Set)` 纯 seam（无需 un-load SPI）：`assertAllPresent()` 查真实注册集后委托 `checkAgainst`，测试直接喂缺 hi 的集合验 fail-closed。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.LocaleAssertionTest"`
Expected: FAIL — `LocaleAssertion` 不存在。

- [ ] **Step 3: 写 `LocaleAssertion`**

Create `aster-replay-runner/src/main/java/io/aster/replay/runner/LocaleAssertion.java`：
```java
package io.aster.replay.runner;

import java.util.Set;

/**
 * runner 启动时断言 4 locale（en/zh/de/hi）SPI 全在 classpath。缺任一即 fail-closed 抛异常——
 * 防 byte-parity 头号陷阱（locale 在 :replay 是 testRuntimeOnly，若 runner 打包漏提升
 * runtimeOnly，非英文 replay 会静默分叉。此断言把「静默」变「启动即失败」）。
 */
public final class LocaleAssertion {
    private LocaleAssertion() {}

    public static final Set<String> REQUIRED_LOCALES = Set.of("en", "zh", "de", "hi");

    /**
     * 断言 4 locale 全部经 SPI 注册（生产入口）。★实现者：调 core LexiconRegistry 真实 API
     * （discoverPlugins() 触发 ServiceLoader 扫描 + 查询已注册 locale 集合），委托 checkAgainst。
     * 用编译 oracle 对齐真实 API（方法名/返回类型）。
     */
    public static void assertAllPresent() {
        // 见实现者注：discoverPlugins() 后取已注册 locale 集 present，再 checkAgainst(present)。
        java.util.Set<String> present = queryRegisteredLocales();  // 实现者按 LexiconRegistry API 实现
        checkAgainst(present);
    }

    /**
     * 纯校验 seam（可测，无 SPI 依赖）：present 须涵盖 REQUIRED_LOCALES，缺任一 → fail-closed 抛。
     * ★负向测试直接喂缺 locale 集合验此方法，无需 un-load SPI。
     */
    static void checkAgainst(Set<String> present) {
        java.util.Set<String> missing = new java.util.TreeSet<>(REQUIRED_LOCALES);
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("缺失 locale SPI: " + missing
                + "——检查 runner build.gradle 是否把 asterLibs.{en,zh,de,hi} 提升为 runtimeOnly");
        }
    }

    /** ★实现者：调 aster.core.lexicon.LexiconRegistry.discoverPlugins() + 查已注册 locale 集。
     *  返回如 {"en","zh","de","hi"}（locale 短码，与 REQUIRED_LOCALES 同口径）。用编译 oracle 校准。 */
    private static Set<String> queryRegisteredLocales() {
        throw new UnsupportedOperationException("implement per LexiconRegistry API");
    }
}
```
★实现者注（load-bearing）：`queryRegisteredLocales` 读 `aster.core.lexicon.LexiconRegistry`——`discoverPlugins()`（触发 SPI 扫描，见 [[lexicon-spi-loading-race]] 3 遍重试）后取已注册 locale 短码集（与 `REQUIRED_LOCALES` 的 en/zh/de/hi 同口径；若 registry 用 en-US/zh-CN 全码，须归一到短码）。用编译 oracle 校准真实 API。`checkAgainst` 已是纯逻辑，负向测试覆盖 fail-closed。
★实现者注（load-bearing）：读 `aster-lang-core` 的 `aster.core.lexicon.LexiconRegistry`——它有 `discoverPlugins()`（触发 SPI 扫描，见 [[lexicon-spi-loading-race]] 3 遍重试）+ 查询已注册 locale 的方法。实现 `assertAllPresent` = discoverPlugins 后取已注册 locale 集，对 `REQUIRED_LOCALES` 求差，缺则 throw。**用编译 oracle 校准真实 API**（方法名/返回类型），不硬猜。

- [ ] **Step 4: 在 `RunnerMain.run()` 起始调断言**

Modify `RunnerMain.run()`，在读请求前加：
```java
    public static int run(InputStream in, PrintStream out) {
        RunnerEnvelope envelope;
        try {
            LocaleAssertion.assertAllPresent();   // ★启动 fail-closed：缺 locale 立即失败
            RunnerRequest req = MAPPER.readValue(in, RunnerRequest.class);
            envelope = execute(req);
        } catch (Exception e) {
            envelope = RunnerEnvelope.error("INTERNAL", String.valueOf(e.getMessage()), "parse");
        }
        // ... 余下不变
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :aster-replay-runner:test --tests "io.aster.replay.runner.LocaleAssertionTest"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add aster-replay-runner/src/main/java/io/aster/replay/runner/LocaleAssertion.java aster-replay-runner/src/main/java/io/aster/replay/runner/RunnerMain.java aster-replay-runner/src/test/java/io/aster/replay/runner/LocaleAssertionTest.java
git commit -m "feat(s2-1a-2): 四 locale 启动断言 fail-closed（byte-parity 头号陷阱守门）"
```

---

### Task 6: 最小 arm64 stock-JRE Dockerfile

**Files:**
- Create: `aster-replay-runner/Dockerfile`
- Create: `aster-replay-runner/.dockerignore`

**Interfaces:**
- Consumes: `./gradlew :aster-replay-runner:installDist` 产的 `build/install/aster-replay-runner/`（`bin/` 启动脚本 + `lib/*.jar`）。
- Produces: arm64 镜像，ENTRYPOINT 跑 runner 启动脚本读 stdin。

- [ ] **Step 1: 写 Dockerfile**

Create `aster-replay-runner/Dockerfile`（仿 `Dockerfile.jvm` 的 arch 断言，但 stock JRE 无 Quarkus；COPY application install 目录）：
```dockerfile
# aster-replay-runner 最小 arm64 stock-JRE 镜像。
# ★集群节点 ARM64（OCI Ampere），必须真 arm64（承 Dockerfile.jvm arch 断言，防 QEMU 误标）。
# stock Zulu JRE（非 GraalVM JDK）——Truffle 走解释器模式（engine.WarnInterpreterOnly=false 已在 :replay 内设）。
FROM --platform=$TARGETPLATFORM azul/zulu-openjdk-alpine:25-jre

ARG TARGETARCH
# arch 断言：防 buildx/QEMU 跨构建静默产 amd64 却标 arm64 → 节点 exec format error。
RUN set -eux; \
    machine="$(uname -m)"; \
    case "$TARGETARCH" in \
        arm64) expect="aarch64" ;; \
        amd64) expect="x86_64" ;; \
        *) echo "unsupported TARGETARCH=$TARGETARCH"; exit 1 ;; \
    esac; \
    [ "$machine" = "$expect" ] || { echo "arch mismatch: uname=$machine expect=$expect (TARGETARCH=$TARGETARCH)"; exit 1; }

WORKDIR /app
# application installDist 产物：bin/ 启动脚本 + lib/*.jar（locale 四 jar 物理分开，SPI 零合并）。
COPY build/install/aster-replay-runner/ /app/

# 最小权限：非 root。
RUN addgroup -S runner && adduser -S runner -G runner
USER runner

# runner 读 stdin JSON → stdout envelope。启动脚本名 = application project name。
ENTRYPOINT ["/app/bin/aster-replay-runner"]
```

Create `aster-replay-runner/.dockerignore`：
```
src/
build/classes/
build/tmp/
*.md
```

- [ ] **Step 2: Commit**（Dockerfile 本身无单测，Task 0 真跑验证）

```bash
git add aster-replay-runner/Dockerfile aster-replay-runner/.dockerignore
git commit -m "feat(s2-1a-2): 最小 arm64 stock-JRE runner Dockerfile"
```

---

### Task 7: 【Task 0 风险门】podman arm64 真跑 + byte-identical 验证脚本

**Files:**
- Create: `aster-replay-runner/src/test/resources/parity-corpus/`（固定 corpus：import-free 子集，四 locale）
- Create: `aster-replay-runner/scripts/gen-expected.sh`（经 aster-api evaluateSource 产权威 expected.json）
- Create: `src/test/java/io/aster/replay/parity/GenExpectedCorpusTest.java`（@QuarkusTest，驱动 corpus 经 aster-api 路径产 expected）
- Create: `aster-replay-runner/scripts/task0-arm64-parity.sh`（构建镜像 + 真跑 corpus + 与 aster-api 权威 expected byte-identical，排除 toolchainId）

**Interfaces:**
- Consumes: Task 6 的 Dockerfile、aster-api 生产 `evaluateSource(...replayCapture:true)` 作**权威对照**（expected 唯一来源）。
- Produces: 可复现脚本，退出码 0 = Task 0 门通过（stock JRE+Truffle 真跑 + byte-identical）；非 0 = 门失败（回头改 A/B）。

- [ ] **Step 1: 建固定 corpus**

在 `aster-replay-runner/src/test/resources/parity-corpus/` 放 import-free policy fixtures（每个 = `<name>.req.json`，schema ② 请求）。**从 aster-api/`:replay` 现有 replay 测试语料挑 import-free 子集**（不新编，复用已验证的——grep `Module .*\nRule` in `replay/src/test`/`src/test` 的 .aster/.json fixture）。

覆盖矩阵（至少各 1）：
- **en-US**：`en-basic.req.json`（如 `{"tenantId":"tenant-1","source":"Module probe.\nRule main given x as Int, produce Int:\n  Return x.","input":{"x":1},"locale":"en-US","functionName":"main","aliasSet":null}`）。
- **zh-CN / de-DE / hi-IN**：各 1 个对应 locale 的 import-free policy（★locale 覆盖是 byte-parity 头号陷阱的核心验证——非英文 locale 若 SPI 未装载会分叉）。
- **Decimal / Date 合规原语**：各 1 个用 `Decimal`/`Date.*` 的 import-free policy（承合规原语已上线）。
- **代表性决策形态**：1 个多分支决策 policy（如信贷 APPROVED/REFER）。

★实现者：确切 CNL 语法/locale 串从真实 fixture 抄，用「Task 5 集成测试绿 + gen-expected.sh 能产非空 expected」双 oracle 校准每个 fixture 真能编译执行。

- [ ] **Step 2: 写 Task 0 验证脚本**

Create `aster-replay-runner/scripts/task0-arm64-parity.sh`：
```bash
#!/usr/bin/env bash
# Task 0 风险门：podman 真构建 arm64 runner 镜像，真跑固定 corpus，
# 与 aster-api 权威对照 byte-identical（★排除 toolchainId，只比 canonical* + replayabilityStatus + traceHash）。
# 退出 0 = 门通过；非 0 = 门失败（stock JRE+Truffle 跑不起 或 有分叉 → 回头改 A/B）。
set -euo pipefail

cd "$(dirname "$0")/.."   # aster-replay-runner/
ROOT="$(cd ../ && pwd)"   # aster-api/

echo "== 1. installDist =="
(cd "$ROOT" && ./gradlew :aster-replay-runner:installDist -q)

echo "== 2. build arm64 image (真 arm64, arch 断言在 Dockerfile) =="
podman build --platform linux/arm64 -t aster-replay-runner:task0 .

echo "== 3. 真跑 corpus，逐个比对 =="
CORPUS_DIR="src/test/resources/parity-corpus"
fail=0
for req in "$CORPUS_DIR"/*.req.json; do
    name="$(basename "$req" .req.json)"
    # runner 真跑（容器内 stock JRE + Truffle 解释器），取 stdout 最后一行 envelope
    runner_out="$(podman run --rm -i --platform linux/arm64 aster-replay-runner:task0 < "$req" | tail -n 1)"
    # 权威对照：aster-api 生产 evaluateSource（replayCapture=true）产的 ReplayMetadata
    #   —— 期望值预生成到 <name>.expected.json（排除 toolchainId），或运行时调 aster-api。
    #   本脚本比对 canonicalInputHash/canonicalOutputHash/canonicalizationVersion/
    #   replayabilityStatus/traceHash 五字段逐字节相等（jq 提取，排除 toolchainId）。
    runner_norm="$(echo "$runner_out" | jq -S '.replayMetadata
        | {canonicalInputHash, canonicalOutputHash, canonicalizationVersion, replayabilityStatus, traceHash}')"
    expected_norm="$(jq -S '{canonicalInputHash, canonicalOutputHash, canonicalizationVersion, replayabilityStatus, traceHash}' \
        "$CORPUS_DIR/$name.expected.json")"
    if [ "$runner_norm" != "$expected_norm" ]; then
        echo "❌ PARITY DIVERGENCE: $name"; diff <(echo "$expected_norm") <(echo "$runner_norm") || true
        fail=1
    else
        echo "✅ $name byte-identical (toolchainId 已排除)"
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "== Task 0 门失败：runner 在 stock JRE/arm64 下与 aster-api 有分叉 → 回头改 A/B =="
    exit 1
fi
echo "== Task 0 门通过：stock Zulu JRE 25 + Truffle 解释器真跑 + byte-identical =="
```

★**期望值 `<name>.expected.json` 生成（权威对照 = aster-api，与 spec L133 一致——Codex 抓的口径冲突）**：

★**权威对照统一口径**：expected = **aster-api 生产 `evaluateSource(...replayCapture:true)` 产的 ReplayMetadata**（spec L133 定义的权威对照），**非** host runner 自产（避免 runner-vs-runner 自证）。gen-expected.sh 经 aster-api 测试入口跑 corpus：

新增 `aster-replay-runner/scripts/gen-expected.sh`：
```bash
#!/usr/bin/env bash
# 产 expected.json 基线 = aster-api 生产 evaluateSource(replayCapture=true) 对 corpus 的 ReplayMetadata。
# ★权威对照是 aster-api（spec L133），非 host runner——Task 0 证 runner(容器/arm64/stock-JRE)
#   与 aster-api(host/生产路径) byte-identical（排除 toolchainId）。
# 实现：经 aster-api 的 replay 测试入口（@QuarkusTest 或已有 evaluateForCapture 测试 harness）
#   对每个 corpus 请求跑一次，取 ReplayMetadata 写 expected.json。
# ★实现者：aster-api 已有 evaluateSource 集成测试（grep replayCapture in src/test）——复用其 harness
#   驱动 corpus，而非新起 HTTP server。用 :test oracle 校准入口。
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(cd ../ && pwd)"
CORPUS_DIR="src/test/resources/parity-corpus"
# 经一个专用生成测试（GenExpectedCorpusTest，@QuarkusTest）跑 corpus → 写 expected.json：
(cd "$ROOT" && ./gradlew :test --tests "io.aster.replay.parity.GenExpectedCorpusTest" \
    -Dparity.corpus.dir="$PWD/$CORPUS_DIR" -Dparity.gen.expected=true -q)
echo "expected.json 已由 aster-api evaluateSource 路径生成（权威对照）"
```
+ 新增 `src/test/java/io/aster/replay/parity/GenExpectedCorpusTest.java`（@QuarkusTest）：读 `-Dparity.corpus.dir` 下 `*.req.json`，经 aster-api `evaluateSource`(replayCapture=true) 产 ReplayMetadata，写同名 `.expected.json`（★实现者复用现有 evaluateSource 集成测试 harness 的调用方式，见 aster-api `src/test` 里 replayCapture 相关 IT）。

★**对照语义的诚实边界（写进脚本旁 README）**：expected = **aster-api（host/生产路径）** 的输出；candidate = **runner（arm64 容器/stock-JRE）**。Task 0 证的是 **runner 打包+容器+arm64+stock-JRE 环境相对 aster-api 有无分叉**（含 runner 复用 `:replay` 是否在这些环境下仍产同结果），**不是**算法独立性（那要 TS 二引擎，spec 已定非本 MVP）。与 spec「Task 0 = Docker/arm64/stock-JRE 环境 parity」+「权威对照=aster-api evaluateSource」一致。

- [ ] **Step 3: 真跑 Task 0 门**

Run: `chmod +x aster-replay-runner/scripts/task0-arm64-parity.sh && aster-replay-runner/scripts/task0-arm64-parity.sh`
Expected: 退出 0，每个 corpus fixture 打印 `✅ byte-identical`，末行 `Task 0 门通过`。
★若失败（stock JRE 跑不起 Truffle / 有分叉）：**停止**，把失败输出反馈给主 AI——B 的 Dockerfile 方案可能须改 GraalVM JDK 基镜像（这正是 Task 0 作为风险门的价值）。

- [ ] **Step 4: Commit**

```bash
git add aster-replay-runner/src/test/resources/parity-corpus/ aster-replay-runner/scripts/task0-arm64-parity.sh aster-replay-runner/scripts/gen-expected.sh src/test/java/io/aster/replay/parity/GenExpectedCorpusTest.java
git commit -m "feat(s2-1a-2): Task 0 风险门——podman arm64 真跑 + byte-identical parity 验证"
```

---

## 验证（本 slice 完整，本地可复现）

```bash
# 全 module 单测
./gradlew :aster-replay-runner:test
# application 打包
./gradlew :aster-replay-runner:installDist
# 产权威 expected.json（aster-api evaluateSource 路径）
aster-replay-runner/scripts/gen-expected.sh
# Task 0 风险门（podman arm64 真跑 + 与权威 expected byte-identical）
aster-replay-runner/scripts/task0-arm64-parity.sh
# 不破坏 aster-api 主构建
./gradlew compileJava :replay:build
```

## 破坏性 / 迁移

- **纯增量**：新 module `aster-replay-runner`，不改 `:replay`/aster-api 主代码（只 `settings.gradle` 加 include）。零生产影响（不接任何真流量）。
- Task 0 若失败：不推进 C–F；回头改 A/B 的 Dockerfile/打包（可能 GraalVM JDK 基镜像）。

## 范围外（本 slice 不做，留 C–F 计划）

- ❌ 子系统 C（CI 签名 workflow）、D（CI parity 门持续化）、E（launcher 微服务）、F（cloud 触发）。
- ❌ SPIRE/签名（S2-1b）、finalization receipt（S2-1c）、受签 ModuleClosure。

## 交叉审查

每 Task：Claude 生成 → Codex 审（禁止自审）。整支：Codex 终审。
