# S2-1a-1 Executor 提取 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 CNL executor implementation（`DynamicCnlExecutor` + 8-文件闭包）从 aster-api 移入共享 `aster-replay-core`，`ModuleResolver` 反转成 `ModuleGraphResolver` 接口——**byte-identical 不改行为**——使未来 β runner 复用**同一份** executor（消除执行分叉）。

**Architecture:** 8 个 pure 文件（executor+parser+parser 辅助+异常）移入 core 保原 FQCN/package；`ModuleResolver`（Panache/DB，不能移）留 aster-api，实现新的 core `ModuleGraphResolver` 接口；executor 剥 CDI 注解成 POJO（双构造器：no-arg 兼容现有测试 + 参数构造供 CDI）；aster-api 加 `@Produces @ApplicationScoped DynamicCnlExecutor` producer；`JacksonMappers`（全应用通用，不移）→ core 用等价 core-local mapper + canonical-byte parity fixture。

**Tech Stack:** Java 25、Gradle、Quarkus（producer 侧）、GraalVM Polyglot 25.0.3、ANTLR 4.13.2、JUnit5、aster-lang core/truffle。

## Global Constraints

- **byte-identical 铁律**：纯提取重构，aster-api 对外行为逐字节不变。验收 = 全回归绿 + 现有 aster-api 测试原地保留绿 + 字符化测试 `PolicyEvaluationReplayOrderingTest`（S2-1a-0 建）**unchanged + 绿** + `PublicApiContractTest` golden 不变 + integrationTest（HMAC/quota/**多模块 import** 路径）绿 + **mapper canonical-byte parity fixture 绿**。
- **闭包完成的唯一判据 = `:aster-replay-core:compileJava` + aster-api `compileJava` + `runtimeClasspath` 绿**，非人工数文件。★若编译报缺类/缺依赖，补入闭包（可能有二阶传递依赖研究未穷尽）——这是预期的，编译是 oracle。
- **保原 FQCN/package**：移入的 8 类**不改 package**（`io.aster.policy.parser`/`io.aster.policy.api.convert`/`io.aster.policy.module`，**不**改成 `io.aster.replay.core`）→ (a) 消费者 import 不改（经 `project(':aster-replay-core')` 解析）；(b) 无 `module-info.java`=Java 包私有按 package 判，同包 aster-api 测试跨 project 仍可访问包私有钩。
- **8 文件 move 集**：`DynamicCnlExecutor`(+嵌套 ExecutionResult/CoreIrCacheKey/CompiledCoreIr/CacheStats/3 异常)、`InProcessCnlParser`、`AliasOverlayLexicon`、`CnlErrorListener`(+嵌套 Diagnostic)、`UserAliasValidator`、`EntryPointSelector`、`NamedContextMapper`、`ModuleResolutionException`。**不移**：`ModuleResolver`（Panache）、`PolicyVersion`、`JacksonMappers`（通用）。
- **ModuleGraphResolver 接口纯**：`ModuleGraph resolveGraph(String tenantId, CoreModel.Module rootCore, List<Decl.Import> rootImports, String locale)`——返回纯 aster-lang record，**不得**让 `PolicyVersion`/Panache 跨 seam。`ModuleResolver` 留 aster-api 实现它。
- **executor 双构造器**：保 public no-arg（现状单模块：resolver=null/modulesEnabled=false；★4 测试站点 `new DynamicCnlExecutor()` 依赖：`UserAliasCompileTest:169/184`、`DynamicCnlExecutorCacheTest:68/107`——删则破 API）+ 新 `DynamicCnlExecutor(ModuleGraphResolver, boolean modulesEnabled)`。多模块 enabled+resolver=null → **fail-closed 抛明确异常非 NPE**（保现状）。
- **异常原样透传**：3 异常嵌套 `DynamicCnlExecutor` 随外类移+保 FQCN；`ModuleResolutionException` 移；`CnlParseException`(嵌套 InProcessCnlParser)+其 `Diagnostic`(CnlErrorListener)移 → resource 4 catch（`DynamicCnlExecutor.AmbiguousEntryException/ModuleExecutionException/DynamicExecutionException` + `InProcessCnlParser.CnlParseException`，`PolicyEvaluationResource:606/618/635/642`）**保持不变**。
- **JacksonMappers 不移，core-local mapper 是引用替换须 parity**：core 定义等价 mapper（现状 `JacksonMappers.DEFAULT` = vanilla `new ObjectMapper()`）；移入的 `DynamicCnlExecutor`/`InProcessCnlParser`/`UserAliasValidator` 对 `JacksonMappers.DEFAULT` 的引用改用 core-local mapper；**canonical-byte parity fixture 守门**（Core IR JSON/decimal+E-notation/key ordering/Unicode+locale/null/nested/ReplayMetadata fixtures 逐字节，影响 hash，PR-blocking）。
- **CDI producer**：`@Produces @ApplicationScoped DynamicCnlExecutor`（★显式 scope 非 @Dependent）注入 `ModuleResolver`（作 ModuleGraphResolver）+ 读 `@ConfigProperty aster.modules.enabled` → `new DynamicCnlExecutor(resolver, enabled)`；满足 `@Inject DynamicCnlExecutor`（`TruffleRuntimeHealthCheck`/`ReplayExecutorAdapter`）。static 调用者（`HotPlugLexiconLoader`/`LexiconAvailabilityService` 的 `clearCompilationCaches()`）FQCN 保留不改。
- **core 新增依赖（真实版本）**：`org.graalvm.polyglot:polyglot:25.0.3`(impl)、`org.graalvm.sdk:graal-sdk:25.0.3`(impl)、`org.graalvm.truffle:truffle-api:25.0.3`(impl)、`org.graalvm.truffle:truffle-runtime:25.0.3`+`truffle-compiler:25.0.3`+`org.graalvm.compiler:compiler:25.0.3`(runtimeOnly)、`org.jboss.logging:jboss-logging:3.6.3.Final`(impl)、`org.antlr:antlr4-runtime:4.13.2`(impl，无条件——parser 直接 import)。locale test：`testRuntimeOnly asterLibs.en/zh/de/hi`（hi=独立 `asterLibs.hi`）。★版本以 aster-api `runtimeClasspath` 实际解析为准（graal-sdk 有 25.0.3→23.1.2 对齐，用 aster-api 有效版本）。
- 中文注释；NEVER `git add -A`（显式路径）；commit 页脚 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`；本地验证（起 Postgres aster_policy）；禁 CI 外包。

## 权威事实（实证，勿重新假设）

- `settings.gradle` `include 'aster-replay-core'` 已在（S2-1a-0）；core `build.gradle` 现有 jackson-databind:2.22.0 + asterLibs.core/truffle/runtime + junit-jupiter:6.1.1。
- `DynamicCnlExecutor` CDI 面：`@ApplicationScoped`(L43)+`@Inject ModuleResolver`(L62-63)+`@ConfigProperty aster.modules.enabled`(L65-66)——别无。私有 `executeInternal` 已把 `moduleResolver, modulesEnabled` 作方法参数穿线(L286/294/304/405)；static `execute`/`executeWithContext` 传 null resolver。`resolveGraph` 调 L433。SHARED_ENGINE(L79)/static 缓存纯 static。
- `ModuleResolver.resolveGraph`(L34) 返回 `aster.core.module.ModuleGraph`(纯 record)；`PolicyVersion`(Panache)仅私有方法 L94/135/155 用，不跨 resolveGraph 签名。
- 消费者：`TruffleRuntimeHealthCheck`(`@Inject`,null-check)、`ReplayExecutorAdapter`(`@Inject`,调实例)、`HotPlugLexiconLoader`(static clearCompilationCaches)、`LexiconAvailabilityService`(static)、`PolicyEvaluationResource`(import 供 catch)。
- InProcessCnlParser 依赖：`AliasOverlayLexicon.wrap`(L139)、`CnlErrorListener`(L159)、`UserAliasValidator.validate`(L114)、`JacksonMappers.DEFAULT`(L303)。`CnlParseException` 嵌套持 `CnlErrorListener.Diagnostic`。
- 分支：aster-api 当前 `main`——Task 0 建分支。

---

## File Structure

**修改**：
- `aster-replay-core/build.gradle`（加 graalvm/antlr/jboss-logging deps + locale testRuntimeOnly）
- `PolicyEvaluationResource.java`（catch 不变；仅确认 import 仍解析——多半 0 改）
- `ReplayExecutorAdapter.java`（无改——仍 `@Inject DynamicCnlExecutor` 调实例）

**移动（git mv 到 core，保 package）**：8 文件（见 move 集）。

**新建**：
- `aster-replay-core/.../parser/ReplayMappers.java`（core-local mapper，替 JacksonMappers.DEFAULT 引用）— 或复用现有 core 位置
- `aster-replay-core/.../module/ModuleGraphResolver.java`（接口）
- aster-api `.../parser/DynamicCnlExecutorProducer.java`（`@Produces @ApplicationScoped`）
- `aster-replay-core/.../parser/ReplayMappersParityTest.java`（canonical-byte parity）

**改（aster-api 保留）**：
- `ModuleResolver.java`（`implements ModuleGraphResolver`）

---

## Task 0: 建分支 + core 依赖矩阵（graalvm/antlr/jboss-logging/locale）

**Files:** branch; Modify `aster-replay-core/build.gradle`

**Interfaces:** Produces: core 有 executor/parser 运行所需全部 compile+runtime 依赖，为后续 move 铺路（空移前先备依赖，否则 move 后编译失败混淆「缺依赖」vs「缺类」）。

- [ ] **Step 1: 建分支**
```bash
cd /Users/rpang/IdeaProjects/aster-api
git checkout -b p0a-s2-1a-1-executor-extraction
```

- [ ] **Step 2: 核对 aster-api 实际解析版本（真实版本 oracle）**
```bash
./gradlew dependencies --configuration runtimeClasspath 2>/dev/null | grep -iE 'graalvm.*(polyglot|sdk|truffle|compiler)|antlr4-runtime|jboss-logging:' | sed 's/[|+\\ ]*//' | grep -oE '[a-z].*:[0-9][0-9A-Za-z.-]*' | sort -u
```
记录实际版本（用于 build.gradle pin；graal-sdk 若显示 `25.0.3 -> X` 用箭头右侧的有效版本）。

- [ ] **Step 3: 加依赖到 core build.gradle**

在 `aster-replay-core/build.gradle` 的 `dependencies {` 块加（版本用 Step 2 实测；下为预期值）：
```groovy
    // S2-1a-1：executor/parser 运行所需（core 无 Quarkus BOM，全显式声明，不靠 aster-api transitive）
    implementation 'org.graalvm.polyglot:polyglot:25.0.3'
    implementation 'org.graalvm.sdk:graal-sdk:25.0.3'
    implementation 'org.graalvm.truffle:truffle-api:25.0.3'
    runtimeOnly    'org.graalvm.truffle:truffle-runtime:25.0.3'
    runtimeOnly    'org.graalvm.truffle:truffle-compiler:25.0.3'
    runtimeOnly    'org.graalvm.compiler:compiler:25.0.3'
    implementation 'org.jboss.logging:jboss-logging:3.6.3.Final'
    implementation 'org.antlr:antlr4-runtime:4.13.2'   // parser 直接 import，无条件
    // locale SPI（byte-parity 需全 locale parse；hi 是独立 artifact 非 locales-hi）
    testRuntimeOnly asterLibs.en
    testRuntimeOnly asterLibs.zh
    testRuntimeOnly asterLibs.de
    testRuntimeOnly asterLibs.hi
```
★核对 `asterLibs.hi` 是否是正确 catalog 访问名（对应 `cloud.aster-lang:aster-lang-hi`）；若 catalog 无 `hi` 访问器，用实际坐标。

- [ ] **Step 4: 验证 core 仍编译（空加依赖不破坏）**
```bash
./gradlew :aster-replay-core:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL（依赖能解析）。★若某 graalvm/antlr 坐标解析失败 → 用 Step 2 实测坐标修正。

- [ ] **Step 5: Commit**
```bash
git add aster-replay-core/build.gradle
git commit -m "$(cat <<'EOF'
feat(s2-1a-1): aster-replay-core 加 executor/parser 运行依赖

graalvm polyglot/sdk/truffle-api(+runtime)+jboss-logging+antlr4-runtime 无条件
显式声明(core 无 Quarkus BOM, 不靠 aster-api transitive); locale SPI 四套
testRuntimeOnly(hi 独立 artifact)。为 executor 移入铺路。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 1: ModuleGraphResolver 接口 + ModuleResolver 实现它（seam 先立）

**Files:** Create `aster-replay-core/.../module/ModuleGraphResolver.java`; Modify aster-api `ModuleResolver.java`

**Interfaces:** Produces: core `io.aster.replay.core.module.ModuleGraphResolver`（或按现有 core 包约定）单方法接口；aster-api `ModuleResolver implements ModuleGraphResolver`。为 Task 2 executor retype 铺路。

★**先立 seam**：executor 尚未移，先建接口 + 让 ModuleResolver 实现它（此时 executor 仍用具体 ModuleResolver，行为不变），Task 2 再把 executor 字段 retype 到接口。这样每步可编译。

- [ ] **Step 1: 写接口**

Create `aster-replay-core/src/main/java/io/aster/replay/core/module/ModuleGraphResolver.java`：
```java
package io.aster.replay.core.module;

import aster.core.ast.Decl;
import aster.core.ir.CoreModel;
import aster.core.module.ModuleGraph;
import java.util.List;

/**
 * module 图解析的 core seam。aster-api 提供 DB-backed 实现（ModuleResolver）；
 * 未来 β runner 提供受签 ModuleClosure 实现。
 *
 * <p>★返回纯 aster-lang {@link ModuleGraph}，参数全纯——PolicyVersion/Panache
 * 不得跨此 seam（保证 core 无 DB 依赖）。
 */
public interface ModuleGraphResolver {
    ModuleGraph resolveGraph(String tenantId, CoreModel.Module rootCore,
                             List<Decl.Import> rootImports, String locale);
}
```
★核对 `ModuleResolver.resolveGraph` 的确切签名（L34）+ `Decl.Import`/`ModuleGraph`/`CoreModel.Module` 的真实 FQCN，逐字对齐。

- [ ] **Step 2: ModuleResolver 实现接口**

`ModuleResolver.java`：类声明加 `implements io.aster.replay.core.module.ModuleGraphResolver`；`resolveGraph` 加 `@Override`。签名不变（本就匹配）。

- [ ] **Step 3: 编译 + 单测**
```bash
./gradlew :aster-replay-core:compileJava compileJava test 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL（接口 + ModuleResolver 实现；executor 仍用具体类型，行为不变）。

- [ ] **Step 4: Commit**
```bash
git add aster-replay-core/src/main/java/io/aster/replay/core/module/ModuleGraphResolver.java src/main/java/io/aster/policy/module/ModuleResolver.java
git commit -m "$(cat <<'EOF'
feat(s2-1a-1): ModuleGraphResolver 接口 + ModuleResolver 实现

core 单方法接口(返回纯 ModuleGraph 非 Panache); aster-api ModuleResolver
implements 它。为 executor retype 铺路(此步 executor 仍用具体类型, 行为不变)。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 8 文件 move 入 core + core-local mapper + executor 双构造器 + retype 接口

**Files:** git mv 8 files; Create core-local mapper; Modify moved DynamicCnlExecutor (CDI 剥 + 双构造器 + retype); Modify moved parser classes (JacksonMappers→core mapper)

**Interfaces:** Consumes: Task 0 依赖 + Task 1 接口。Produces: executor+parser 闭包在 core，executor 是 POJO 双构造器依赖 `ModuleGraphResolver`。此时 aster-api 的 `@Inject DynamicCnlExecutor` **暂时坏**（无 CDI bean）——Task 3 producer 修。故 Task 2 只到「core 编译 + core 侧测试」，aster-api 编译到 Task 3 才绿。

★**这是最大一刀**——分步做，每子步可编译到 core 层。

- [ ] **Step 1: git mv 8 文件到 core（保 package 目录）**
```bash
cd /Users/rpang/IdeaProjects/aster-api
for f in \
  parser/DynamicCnlExecutor parser/InProcessCnlParser parser/AliasOverlayLexicon \
  parser/CnlErrorListener parser/UserAliasValidator parser/EntryPointSelector \
  api/convert/NamedContextMapper module/ModuleResolutionException; do
  src="src/main/java/io/aster/policy/$f.java"
  dst="aster-replay-core/src/main/java/io/aster/policy/$f.java"
  mkdir -p "$(dirname "$dst")"
  git mv "$src" "$dst"
done
```

- [ ] **Step 2: 写 core-local mapper（替 JacksonMappers.DEFAULT）**

Create `aster-replay-core/src/main/java/io/aster/replay/core/parser/ReplayMappers.java`（现状 `JacksonMappers.DEFAULT` 是 vanilla `new ObjectMapper()`——核对后等价复制其确切配置）：
```java
package io.aster.replay.core.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * core-local Jackson mapper，等价 aster-api io.aster.common.JacksonMappers.DEFAULT。
 * ★JacksonMappers 是全应用通用类（17 文件用），不移入 replay 模块（ownership 倒置）；
 * core 自带等价 mapper，配置须与 DEFAULT byte-identical（number/decimal 影响 hash）——
 * 由 ReplayMappersParityTest 守门。
 */
public final class ReplayMappers {
    public static final ObjectMapper DEFAULT = /* 逐字等价 JacksonMappers.DEFAULT 配置 */ new ObjectMapper();
    private ReplayMappers() {}
}
```
★先 READ aster-api `JacksonMappers.java` 确认 `DEFAULT` 的确切构造（若有 registerModule/feature 配置须逐字复制）。

- [ ] **Step 3: 移入的 parser 类改用 ReplayMappers**

在 moved `DynamicCnlExecutor`/`InProcessCnlParser`/`UserAliasValidator`，把 `io.aster.common.JacksonMappers.DEFAULT` 引用改为 `io.aster.replay.core.parser.ReplayMappers.DEFAULT`（import + 用点）。★这些是仅有的 JacksonMappers 引用（Task 权威事实 L303 等）。

- [ ] **Step 4: DynamicCnlExecutor 剥 CDI + 双构造器 + retype**

moved `DynamicCnlExecutor.java`：
- 删 `@ApplicationScoped`/`@Inject`/`@ConfigProperty` import + 注解。
- 字段 `ModuleResolver moduleResolver` → `io.aster.replay.core.module.ModuleGraphResolver moduleResolver`；`executeInternal`/`compileCoreIr` 的 `ModuleResolver` 参数(L286/294/304/405) → `ModuleGraphResolver`；`resolveGraph` 调不变（接口同签名）。
- **双构造器**：`public DynamicCnlExecutor() { this(null, false); }`（保 no-arg）+ `public DynamicCnlExecutor(ModuleGraphResolver r, boolean modulesEnabled) { this.moduleResolver = r; this.modulesEnabled = modulesEnabled; }`。
- `modulesEnabled` 从 `@ConfigProperty` 字段 → 普通 final 字段（构造注入）。
- ★多模块 enabled+resolver=null 的 fail-closed 逻辑不变（现 `!imports.isEmpty() && modulesEnabled` 才解引用 resolver，null 时现有明确异常保留）。

- [ ] **Step 5: 编译 core（aster-api 暂不编译——Task 3 修）**
```bash
./gradlew :aster-replay-core:compileJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL。★若报缺类（如某 parser 辅助漏移）→ 补入 move 集（编译是 oracle，闭包可能有二阶依赖）；若报缺依赖 → Task 0 补。**反复直到 core 编译绿**。

- [ ] **Step 6: core 侧 mapper parity + executor 单测**

Create `aster-replay-core/src/test/java/io/aster/replay/core/parser/ReplayMappersParityTest.java`。★**core 不能反依赖 aster-api**，故 parity 用**捕获的 golden 字符串**（同 S2-1a-0 characterization golden 模式）：
- 先在 aster-api 侧（临时 scratch 或现有测试）用 `JacksonMappers.DEFAULT.writeValueAsString(fixture)` 对每个 fixture **捕获一次** golden 输出串，记录进 core 测试作 `expected` 常量。
- core 测试断言 `ReplayMappers.DEFAULT.writeValueAsString(fixture)` == 该 golden，逐字节。
- fixture 集：Core IR JSON（真实 lower 出的样例）/ decimal+E-notation（如 `1.5E10`、`0.1`）/ Map key ordering（多键） / Unicode+locale 字符 / null 字段 / nested list-map / ReplayMetadata canonical 输入。
- ★golden 捕获后写死；若将来 `JacksonMappers.DEFAULT` 配置变，此测试红 = 提醒 core mapper 须同步（正是守门意义）。
```bash
./gradlew :aster-replay-core:test 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL（parity + 移入的 DynamicCnlExecutorCacheTest 若也移则在此；但按 §3 决策测试留 aster-api，故此步只 core 新增 parity/单测）。

- [ ] **Step 7: Commit（core 层，aster-api 暂坏）**
```bash
git add aster-replay-core/src src/main/java/io/aster/policy/parser src/main/java/io/aster/policy/api/convert src/main/java/io/aster/policy/module
git commit -m "$(cat <<'EOF'
refactor(s2-1a-1): 8 文件 executor 闭包 move 入 core + POJO 双构造器

DynamicCnlExecutor+InProcessCnlParser+AliasOverlayLexicon+CnlErrorListener+
UserAliasValidator+EntryPointSelector+NamedContextMapper+ModuleResolutionException
移入 core 保原 package。executor 剥 CDI 成 POJO(保 no-arg+新参数构造),
字段 retype ModuleGraphResolver。JacksonMappers 不移, core 用 ReplayMappers
(canonical-byte parity 守门)。aster-api CDI 待 Task 3 producer。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: aster-api CDI producer + 全量验收（byte-identical 门）

**Files:** Create aster-api `DynamicCnlExecutorProducer.java`; verify consumers/catches/tests

**Interfaces:** Consumes: Task 2 的 POJO executor。Produces: aster-api 的 `@Inject DynamicCnlExecutor` 重新可用（producer 提供 bean）；全 aster-api 编译 + 测试绿 = byte-identical。

- [ ] **Step 1: 写 CDI producer**

Create `src/main/java/io/aster/policy/parser/DynamicCnlExecutorProducer.java`：
```java
package io.aster.policy.parser;

import io.aster.policy.module.ModuleResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * aster-api CDI producer：把 core 的 POJO DynamicCnlExecutor 以 @ApplicationScoped
 * 暴露给 CDI（满足 @Inject DynamicCnlExecutor 的 TruffleRuntimeHealthCheck /
 * ReplayExecutorAdapter）。注入 DB-backed ModuleResolver（作 ModuleGraphResolver）
 * + 读 aster.modules.enabled。★scope 显式 @ApplicationScoped（保原 bean 生命周期）。
 */
@ApplicationScoped
public class DynamicCnlExecutorProducer {

    @Inject
    ModuleResolver moduleResolver;   // implements ModuleGraphResolver（Task 1）

    @ConfigProperty(name = "aster.modules.enabled", defaultValue = "false")
    boolean modulesEnabled;

    @Produces
    @ApplicationScoped
    DynamicCnlExecutor dynamicCnlExecutor() {
        return new DynamicCnlExecutor(moduleResolver, modulesEnabled);
    }
}
```
★核对现有 `@ConfigProperty` 的确切 name/default 与原 executor 一致（L65-66）。

- [ ] **Step 2: 确认消费者 + catch + 测试仍解析**

Run:
```bash
cd /Users/rpang/IdeaProjects/aster-api
echo "=== resource 4 catch 仍引用 executor 嵌套异常(FQCN 保留应无改) ==="
grep -nE 'DynamicCnlExecutor\.(AmbiguousEntry|ModuleExecution|DynamicExecution)Exception|InProcessCnlParser\.CnlParseException' src/main/java/io/aster/policy/rest/PolicyEvaluationResource.java
echo "=== PolicyMetricsTest CacheStats 是否 executor 的(应是它自己的) ==="
grep -n 'CacheStats' src/test/java/io/aster/policy/metrics/PolicyMetricsTest.java | head -3
```
★若 resource catch 或消费者 import 报错，说明某异常/类没随外类移或 package 变了——修（保 FQCN）。若 PolicyMetricsTest 的 CacheStats 确是它自己的类型（不同包），无需动。

- [ ] **Step 3: 全 aster-api 编译**
```bash
./gradlew compileJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL——`@Inject DynamicCnlExecutor` 由 producer 满足；consumers/catches import 保 FQCN 解析。★若报缺 no-arg 构造（某测试）→ 确认 Task 2 保了 no-arg。

- [ ] **Step 4: 全量测试（byte-identical 门）**
```bash
./gradlew test 2>&1 | tail -25
```
Expected: BUILD SUCCESSFUL——现有 aster-api 测试（`DynamicCnlExecutorCacheTest`/`UserAliasCompileTest`/`UserAliasArchTest`/`CnlErrorFriendlyTest` 同包访问移入类的包私有钩 + 4 个 `new DynamicCnlExecutor()` no-arg）+ 字符化测试 `PolicyEvaluationReplayOrderingTest` **unchanged 绿** + `PublicApiContractTest` golden 不变 + core `:test` parity 绿。

- [ ] **Step 5: 集成测试（多模块 import 路径——本刀动了 seam）**
```bash
./gradlew integrationTest 2>&1 | tail -25
```
Expected: BUILD SUCCESSFUL（`InternalCallerFilterHmacIT`/`QuotaChainIT` + 多模块 import IT 经 `ModuleGraphResolver`→`ModuleResolver` DB 解析结果不变）。★留意 S2-1a-0 已知的 pre-existing 无关失败（`TimerCrashRecoveryIT`/`WaadrMetricsResourceIT`）——基线核对区分。

- [ ] **Step 6: Commit**
```bash
git add src/main/java/io/aster/policy/parser/DynamicCnlExecutorProducer.java
git commit -m "$(cat <<'EOF'
feat(s2-1a-1): aster-api DynamicCnlExecutor CDI producer

@Produces @ApplicationScoped 把 core POJO executor 暴露 CDI(注入 ModuleResolver
作 ModuleGraphResolver+读 aster.modules.enabled)。@Inject 消费者/resource 4 catch/
现有测试(同包访问包私有钩+no-arg 构造)全绿。字符化+contract golden+多模块 IT
=byte-identical。

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 全量验收 + 交叉审查（禁止自审）

- [ ] **Step 1: 全量本地验证**
```bash
cd /Users/rpang/IdeaProjects/aster-api
./gradlew :aster-replay-core:test test 2>&1 | grep -iE 'BUILD|FAILED' | tail -5
echo "=== 8 文件在 core, 不在 aster-api ==="
for f in DynamicCnlExecutor InProcessCnlParser AliasOverlayLexicon CnlErrorListener UserAliasValidator EntryPointSelector; do
  ls aster-replay-core/src/main/java/io/aster/policy/parser/$f.java >/dev/null 2>&1 && echo "$f in core" || echo "$f MISSING"
done
echo "=== core 无 Panache/DB 泄漏 ==="
grep -rE 'PanacheEntity|PolicyVersion|jakarta.persistence|io.quarkus' aster-replay-core/src/main/java/ && echo "LEAK" || echo "clean"
echo "=== ModuleResolver 仍在 aster-api ==="
ls src/main/java/io/aster/policy/module/ModuleResolver.java >/dev/null 2>&1 && echo "ModuleResolver stays aster-api"
```
Expected: 全绿；8 文件在 core；core 无 Panache/DB；ModuleResolver 留 aster-api。

- [ ] **Step 2: Codex 交叉审查（禁止自审）**

把全部改动交 Codex 审。审查重点：core 无 Panache/DB 泄漏（ModuleGraphResolver seam 纯）；executor 双构造器保 no-arg 兼容；producer scope @ApplicationScoped；异常 FQCN 保留 4 catch 不变；JacksonMappers 不移 + ReplayMappers canonical-byte parity 逐字节；多模块 import 经接口回归；字符化测试 unmodified + 绿 = byte-identical；依赖矩阵 runtimeClasspath 冻结无偶然 transitive。决策规则：≥90 且「建议通过」→ 合；<80「退回」→ 修；80-89 仔细审。★若 Codex MCP 超时用 codex exec CLI 后台。

- [ ] **Step 3: 交用户决策合入（PR）**

★逐 PR 停·审·合。PR 到 main（aster-api ruleset，CI build/Testcontainers）。

---

## Self-Review（对照 spike）

**Spec coverage**：8 文件 move（Task 2）✓；ModuleGraphResolver 接口 + ModuleResolver 实现（Task 1）✓；executor POJO 双构造器 + retype（Task 2）✓；@Produces @ApplicationScoped producer（Task 3）✓；JacksonMappers 不移 + core-local mapper parity（Task 2 Step 2/6）✓；core 依赖矩阵（Task 0）✓；异常 FQCN 保留 catch 不变（Task 3 Step 2）✓；保原 package + 同包测试原地（Global Constraints + Task 3 Step 4）✓；多模块 import 回归（Task 3 Step 5）✓；ModuleClosure/runner 延后（不在本 plan）✓。

**Placeholder scan**：Task 0 Step 3 版本「预期值」+ Step 2 实测 oracle——非交付占位（有实测校正指令）。Task 2 Step 2 ReplayMappers「逐字等价」+ Step 先 READ JacksonMappers——有明确对齐指令 + parity fixture 守门。core mapper parity 用 golden（core 不能反依赖 aster-api，Task 2 Step 6 已注明从生产捕获 golden）。

**Type consistency**：`ModuleGraphResolver.resolveGraph` 签名 Task 1 定义、Task 2 executor retype 消费、ModuleResolver 实现——三处一致（须核对 aster-api `resolveGraph` 真签名逐字）。`DynamicCnlExecutor` 双构造器 Task 2 定义、Task 3 producer（参数构造）+ 现有测试（no-arg）消费一致。`ReplayMappers.DEFAULT` Task 2 定义 + parser 引用一致。
