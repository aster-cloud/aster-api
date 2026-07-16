# M2 Replay Payload — aster-api Contract Spike（可行性 + 契约定稿）

> **状态：spike 产出，未改生产代码。** 定义 aster-api 在 `replayCapture=true` 时**额外返回完整
> canonical payload 值**（供 cloud 加密持久化做真回放）的契约。对照真实代码核验（见文末引用）。
> 承 `~/.claude/plans/m2-replay-payload-capture.md` 的 PR-M2.1。

## 背景 / 现状（已实证）

M1 已上线：`/evaluate-source?replayCapture=true`（HMAC 内部调用）返回 `ReplayMetadata`——**只有 3 个哈希**
（`canonicalInputHash/canonicalOutputHash/traceHash`）+ 工具链/版本/状态，**不含任何 payload 值**。
cloud 拿哈希只能做漂移检测 + 防篡改，**不能真回放**（缺原始 canonical 值重跑）。

M2 需要：aster-api 额外返回 **canonical input / canonical output / trace 的完整值**，cloud 才能加密存、
日后解密重跑复现。

## ★可行性结论（LOW effort，有一个大前提）

- **canonical input/output 值：LOW effort。** canonical **字符串**已由 public `CanonicalJson.canonicalJson(node, ctx)`
  产出，只是在 `canonicalHash()` 里算完哈希就**丢弃**了字符串。保留 = `ReplayMetadata.tryHash` 同时留下
  canonical 字符串（或新增 `{canonical, hash}` pair 方法避免序列化两次）。无算法改动、无新 canonicalization 逻辑。
- **★契约关键决策：返回 post-lift canonical 字符串，不返回原始值。** 这样 cloud 校验 =
  `sha256(CANONICALIZATION_VERSION + "\n" + shippedString)`，**无需 cloud 重新 canonicalize**——绕开
  `ReplayMetadata.liftDecimals` 的 Java-capture-only 变换（TS 侧 lift + parity fixtures 尚未做，若让 cloud
  从原始值重算会碰这个 parity gap）。返回已 lift 的 canonical 串，cloud 直接 hash 验证即可，零歧义。
- **★最大障碍（非阻塞 payload plumbing，但决定「trace 值」的实际价值）：完整 trace 目前是决策级 stub，
  `steps=[]`。** `TraceStep` 在 `src/main` **零生产填充**（`PolicyEvaluationResource` 建 trace 时硬编码
  `List.of()`）。→ payload 管道 LOW effort，但吐出的 trace 值现在**没有 step 级明细**。真正的 step 级
  可回放 trace 是 executor（`DynamicCnlExecutor`）里的**独立中大工程**，且现在**无 step/深度/大小上限**——
  M2 要单独设计，不能和 payload plumbing 混为一谈。

## 契约定义（M2 replayCapture 扩展）

### 响应扩展
`ReplayMetadata` record（`io.aster.policy.replay.ReplayMetadata`）加 3 个 **nullable** 字段（`@JsonInclude(NON_NULL)`）：

| 字段 | 类型 | 语义 |
|---|---|---|
| `canonicalInput` | `String \| null` | 请求级 context 的 **post-lift canonical JSON 字符串**（`hash(它)==canonicalInputHash`） |
| `canonicalOutput` | `String \| null` | 业务 result 的 post-lift canonical JSON 字符串（`hash(它)==canonicalOutputHash`） |
| `canonicalTrace` | `String \| null` | 决策级 stable trace 的 canonical 字符串（`hash(它)==traceHash`，M2 步骤级前为 decision-level） |

- **只在 `effectiveReplayCapture=true`（现有 HMAC 门控）时填**——与哈希同门控，非授权调用方拿不到（防
  批量白算大 payload 的 CPU/内存放大，与现有门控理由一致）。
- 保持 `@JsonInclude(NON_NULL)`：M1 消费者（不认这 3 字段）不受影响；旧 cloud 忽略即走 M1 路径（向后兼容）。

### 校验契约（cloud 侧）
```
verify(canonicalInput)  == (sha256(CANONICALIZATION_VERSION + "\n" + canonicalInput)  == canonicalInputHash)
verify(canonicalOutput) == (sha256(CANONICALIZATION_VERSION + "\n" + canonicalOutput) == canonicalOutputHash)
verify(canonicalTrace)  == (sha256(CANONICALIZATION_VERSION + "\n" + canonicalTrace)  == traceHash)
```
cloud 落库前**必须逐条自验** `hash(收到的串)==已收到的哈希`；不一致 → fail-closed 不存（防 aster-api 侧串/哈希错配）。

### canonical / parity 口径
- 版本串 `aster-canonical-json/v1`（`CanonicalJson.CANONICALIZATION_VERSION`）；哈希前缀格式
  `version + "\n" + canonicalJson`。返回的字符串就是被 hash 的那个串（post-lift），cloud 逐字节 re-hash 即可。
- TS↔Java canonical **字节级 parity** 是既有不变式（`CanonicalJsonParityTest` golden fixtures）——但仅保证
  「已是 string-on-decimal-path 形式」的输入。**返回 canonical 串绕开此限制**：cloud 不 re-canonicalize，只 re-hash。

### 大小 / 性能
- input 上限已有：`MAX_SOURCE_LENGTH=64KiB`（source）、context 有元素数上限。**response 侧无大小上限**（现有）。
- canonical input/output 串大小与输入同量级，可控。**trace 串 = M2 步骤级 wiring 后无上限**——步骤级实现
  时**必须**加 step 数/深度/总字节上限 + 超限截断策略（本 spike 只定 payload 契约，步骤级 wiring 另立）。

## ★M2 语义边界：漂移检测（A，做）vs 逐字节复现当时决策（B，不做）

M2 只承诺 **A**。B 是数量级更大的另一件事，现架构不为它设计。**对外话术必须严格区分**——说 A 的能力，
不夸大成 B（与「不伪造证据、不夸大能力」的一贯原则一致）。

| | 能做什么 | 语义 | 现架构 |
|---|---|---|---|
| **A. 漂移检测（M2 目标）** | 存 payload + 用**当前**引擎重跑比对 | 「今天的引擎对这条历史决策还会给同样结果吗？可复算、可验证、防篡改、漂移可检」 | 只缺 payload——M2.1a（LOW effort）即解决 |
| **B. 逐字节复现当时决策** | 需保留并拉起**当时**引擎全环境重跑 | 「证明**当年那一刻**引擎怎么裁的，逐字节一致」 | **不为此设计，代价极重** |

### B 的四个真难点（对照真实代码，解释为何不做）
1. **引擎=当前 classpath 那个 JAR，无版本分发。** `core=` 版本来自
   `Canonicalizer.class.getPackage().getImplementationVersion()`（`ToolchainIdentityProvider.java:34`）——
   即此刻链接进进程的 core JAR 版本；执行走**单一** `DynamicCnlExecutor`，**无按版本分发**；build.gradle
   `implementation asterLibs.core` 是**一个** pinned 版本。要用「当时」引擎跑，得换进程/环境加载当时 JAR——
   现架构无「同持多版本、按 executionId 选对应版本」的能力。**最大结构性缺口。**
2. **要封存的是整条工具链的传递闭包，不是一个 JAR。** `runtimeToolchainId` 记 `abi+core+validator+build`，
   但影响决策的还有 core 的**全部传递依赖确切版本** + **JVM 版本本身**（不同 JDK 浮点/字符串/排序可能有差异）
   + validator/lexicon ABI/别名逻辑。逐字节复现 = 这**整个运行环境**都得是当时的——是「封存完整环境」问题。
3. **决定论没保证到「跨版本逐字节」这个强度。** 系统保证**同版本内**确定性（M1 已验），**不保证也不打算**
   保证「不同引擎版本产出逐字节相同」——漂移检测的整个设计前提就是引擎会演进、行为会变（canonical 串都带
   版本号 `aster-canonical-json/v1`，升版换哈希）。故「用当前引擎重跑比对」=A（有价值）；B 是语义完全不同的强承诺。
4. **封存的运维/成本/安全代价重。** 每个曾部署的引擎版本要**永久保留可运行镜像+依赖+JVM**；回放按
   `runtimeToolchainId` 拉起对应隔离环境；版本一多是持续运维/存储负担（几年后几十个版本要保活）；老版本引擎
   可能有已知漏洞却要保持可运行（安全面）。

### 结论
M2 做 A。合规审计通常要的是「决策可复算、可验证、能查引擎有没有偷偷变」——A 满足。B 仅当有**明确监管要求
「逐字节复现历史时刻」**才值得，且是独立立项的大工程（版本化引擎封存 + 隔离拉起 + 保活）。

## 建议 M2.1 落地切分（aster-api 侧）

1. **M2.1a（本契约核心，LOW）**：`CanonicalJson` 加 `canonicalWithHash(node, ctx) → {canonical, hash}`（一次
   序列化出串+哈希）；`ReplayMetadata` 加 3 nullable 串字段 + `tryCanonicalAndHash` 同时留串留哈希；
   `ReplayMetadata.compute` 在 replayCapture 时填串；response wiring 不变。加单测：`hash(返回串)==返回哈希`。
2. **M2.1b（步骤级 trace，MEDIUM–HIGH，独立）**：executor 填 `TraceStep`（含上限/截断）；`canonicalTrace`
   随之从 decision-level 升 step-level。**不阻塞 M2.1a**——M2.1a 先给 input/output + decision-level trace 值，
   已足够 cloud 起 payload 加密落库（PR-M2.2）。

## 代码引用（核验依据）
- `ReplayMetadata` 8 字段只哈希：`src/main/java/io/aster/policy/replay/ReplayMetadata.java:57-66`
- `tryHash` 丢弃 canonical 串：`ReplayMetadata.java:127-144`
- `canonicalJson` public 产串 / `canonicalHash` 丢串：`aster-lang-core/.../canonical/CanonicalJson.java:115,129-135`
- trace `steps=[]` 硬编码、`TraceStep` 零填充：`PolicyEvaluationResource.java:570-579`
- HMAC 门控 `effectiveReplayCapture`：`PolicyEvaluationResource.java:508-515`
- `withReplayMetadata` / `@JsonInclude(NON_NULL)`：`EvaluationResponse.java:39-41,87-89`
- 版本串 + TS parity 不变式：`CanonicalJson.java:18-40`；`stableTraceNode` 排除 executionTimeMs：`ReplayMetadata.java:218-235`
- （B 难点）core 版本=classpath JAR，无版本分发：`ToolchainIdentityProvider.java:33-35`；单一执行器
  `DynamicCnlExecutor`；core 单 pinned 依赖 `build.gradle:86 implementation asterLibs.core`；prod 走
  `Dockerfile.jvm`（`docs/native-image-decision.md:17`）。
