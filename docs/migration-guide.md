# 迁移到 Aster 规则引擎 — 外部团队迁移指南

**面向对象**：正在评估或已决定把现有规则/决策逻辑迁移到 Aster 的工程 + 合规团队。
**本指南的验证边界（诚实声明）**：本指南的 CNL 语法示例经过实测——英文规则在 Java/ANTLR 引擎解析通过、在 TypeScript 引擎编译并执行通过；德文版在 TypeScript 引擎下与英文版**逐字段产出相同决策**。注意这**不是** Java-eval 与 TS-eval 的逐字节对拍（那是引擎层 parity gate 的工作，不在本指南样本的验证范围）。本指南引用的 REST 端点均以本仓库 `src/main/java` 实际暴露的为准。验证样本见 `.claude/analysis/migration-guide/rules/`。

---

## 0. 先判断：你到底需不需要迁

Aster **不是**通用规则引擎的更快替代品。它在吞吐量、规则表达灵活度上不占优，押注的是一个别人很少做的维度：

> **每一笔历史决策都可复现，并证明用的就是当时已审批的那版规则。**

（"复现"= 用固化的规则版本 + 留痕输入重算出相同决策。逐步级的细粒度回放能力请见 §5.1 的当前实现状态——
别按理想态承诺，按本仓库实际端点能力评估。）

只有同时满足这两条，迁移才划算：

1. **强监管 / 法律要求决策可解释**（如信贷拒贷理由披露、保险拒赔说明是法定义务）；
2. **规则频繁变动**——"线上跑的到底是不是已审批的那一版"成为真问题。

典型刚需：金融信贷准入/风控、保险理赔自动核赔。
**反例**：企业访问控制/授权——可解释非刚需，OPA/Cedar 更合适，别迁。

如果你只是想要"更灵活的规则引擎"，本指南到此为止——你大概率不需要 Aster。

---

## 1. 迁移的四个步骤（总览）

| 步骤 | 内容 | 成本 |
|---|---|---|
| **S1 翻译规则** | 把现有规则改写成 Aster CNL（只用 Stable 子集） | 主要工作量 |
| **S2 引入治理** | 起草 → 审批 → 版本固化 → 设默认版本 | 组织成本（常大于技术成本） |
| **S3 接 API 执行** | 调 `POST /api/v1/policies/evaluate` 跑已部署规则 | 轻（一个 REST 端点） |
| **S4 灰度验证** | 与旧引擎并行跑、比对一致性，再切流量 | 中 |

**别一上来全量迁。** 先拿一个真实场景做 PoC，跑通"审批 → 固化 → 执行 → 回放"全链，验证价值再扩。

---

## 2. S1：把规则翻译成 CNL

### 2.1 只用 Stable 子集

1.x 主版本内 Stable 集语法 + 运行时语义**只加不改**（SemVer 承诺）。迁移规则**只用 Stable 集**，
避开 Experimental（workflow async、跨模块 `Use`、effect 系统、PII）和 Excluded（Set/requires）。

迁移决策类规则需要的特性**全部在 Stable 集**：

- 模块声明 `Module`、类型声明 `Define ... has`
- 规则声明 `Rule ... given ..., produce:`、规则间调用
- 局部绑定 `Let ... be ...`
- 条件分支 `If ... / Otherwise`
- 全部运算符：算术（`plus`/`minus`/`times`/`divided by`/`modulo`/`integer divided by`）、
  比较（`less than`/`at least`/`at most`/`is equal to`/`is not equal to`）、逻辑（`and`/`or`/`not`）
- 结构体构造（见 2.3，**语法是命名字段 `with ... set to ...`，有坑**）

### 2.2 一个经过 Java 解析 + TS 执行验证的完整示例（保险理赔自动核赔）

下面这条规则**已实测**：Java/ANTLR 引擎**解析**通过 + TypeScript 引擎**编译并执行**通过（5 个分支全覆盖）。
（注意：这是"Java parse + TS eval"两端各自验证，**不是** Java-eval 与 TS-eval 的逐字节对拍。）

```aster
Module aster.insurance.claim_adjudication.

Define Claim has claimantAge, policyActiveDays, claimAmount, deductible, priorClaims, documentationComplete.

Define Adjudication has outcome, reasonCode, payableAmount, riskScore.

Rule riskScore given priorClaims, policyActiveDays, produce:
  Let tenureFactor be policyActiveDays divided by 30.
  Let claimPenalty be priorClaims times 15.
  Return claimPenalty minus tenureFactor.

Rule payableAfterDeductible given claimAmount, deductible, produce:
  Let net be claimAmount minus deductible.
  If net less than 0
    Return 0.
  Return net.

Rule decide given claim, produce:
  Let score be riskScore(claim.priorClaims, claim.policyActiveDays).
  Let payable be payableAfterDeductible(claim.claimAmount, claim.deductible).
  If claim.policyActiveDays less than 30
    Return Adjudication with outcome set to "DECLINED" and reasonCode set to "POLICY_TOO_NEW" and payableAmount set to 0 and riskScore set to score.
  If claim.documentationComplete is equal to false
    Return Adjudication with outcome set to "PENDING" and reasonCode set to "DOCS_INCOMPLETE" and payableAmount set to 0 and riskScore set to score.
  If score at most 20 and claim.claimAmount at most 5000
    Return Adjudication with outcome set to "APPROVED" and reasonCode set to "AUTO_PAYOUT" and payableAmount set to payable and riskScore set to score.
  If score at most 50
    Return Adjudication with outcome set to "REFER" and reasonCode set to "MANUAL_REVIEW" and payableAmount set to payable and riskScore set to score.
  Return Adjudication with outcome set to "DECLINED" and reasonCode set to "HIGH_RISK" and payableAmount set to 0 and riskScore set to score.
```

实测决策（输入 → 输出，TS 引擎逐分支）：

| 场景 | policyActiveDays | priorClaims | docs | claimAmount | → outcome / reasonCode | payable |
|---|---|---|---|---|---|---|
| 保单太新 | 10 | 0 | ✓ | 3000 | DECLINED / POLICY_TOO_NEW | 0 |
| 资料不全 | 400 | 0 | ✗ | 3000 | PENDING / DOCS_INCOMPLETE | 0 |
| 低风险小额 | 400 | 0 | ✓ | 3000 | APPROVED / AUTO_PAYOUT | 2500 |
| 中等风险 | 400 | 2 | ✓ | 9000 | REFER / MANUAL_REVIEW | 8500 |
| 高风险 | 60 | 5 | ✓ | 9000 | DECLINED / HIGH_RISK | 0 |

### 2.3 ⚠️ 结构体构造：必须用命名字段 `with ... set to ...`

这是迁移者**最容易踩的坑**。要构造一个 `Define` 的类型实例，**不能**用位置式 `TypeName(arg1, arg2)`：

```aster
# ❌ 错误：会被静默当成函数调用，编译通过但执行时抛 "Undefined function 'Adjudication'"
Return Adjudication("DECLINED", "POLICY_TOO_NEW", 0, score).

# ✅ 正确：命名字段，字段间用 and 连接，赋值用 set to
Return Adjudication with outcome set to "DECLINED" and reasonCode set to "POLICY_TOO_NEW" and payableAmount set to 0 and riskScore set to score.
```

位置式写法 `Type(args)` 在**编译期不会报错**（它被降级成普通函数调用），直到**运行时**才暴露
`Undefined function`。务必用 `with ... set to ...` 命名字段形式。

如果规则输出是单个文本结论（不构造结构体），可直接 `Return Text`：

```aster
Rule decide given applicant, produce Text:
  If applicant.creditScore at least 740
    Return "Approved — premium rate".
  Return "Declined — credit score below threshold".
```

---

## 3. 多语言：同一规则，本地化关键词 + 标识符，相同决策

Aster 的卖点之一是规则可用业务母语书写（关键词**和**标识符都本地化），且不同语言版本**逐字段产出相同决策**。

下面是上节理赔规则的**德文版**（关键词如 `Modul`/`Definiere`/`Regel`/`wenn`/`gib zurueck` 本地化，
标识符如 `Schaden`/`risikoWert`/`entscheiden` 也本地化）。**已实测**：与英文版对相同输入产出**逐字段相同决策（5/5）**。

```aster
Modul aster.versicherung.schadenpruefung.

Definiere Schaden hat antragstellerAlter, policeAktivTage, schadenBetrag, selbstbehalt, anzahlVorschaden, unterlagenKomplett.

Definiere Bescheid hat ergebnis, grundCode, zahlbarerBetrag, risikoWert.

Regel risikoWert gegeben anzahlVorschaden, policeAktivTage liefert:
  sei laufzeitFaktor gleich policeAktivTage geteilt durch 30.
  sei schadenStrafe gleich anzahlVorschaden mal 15.
  gib zurueck schadenStrafe minus laufzeitFaktor.

Regel entscheiden gegeben schaden liefert:
  sei wert gleich risikoWert(schaden.anzahlVorschaden, schaden.policeAktivTage).
  ...
  wenn wert hoechstens 50
    gib zurueck Bescheid mit ergebnis setze auf "REFER" und grundCode setze auf "MANUAL_REVIEW" und zahlbarerBetrag setze auf zahlbar und risikoWert setze auf wert.
  ...
```

实测：英文 `decide` 与德文 `entscheiden` 对同一组 5 个理赔输入，决策（outcome/reasonCode/payable/riskScore）
**5/5 逐字段相同**——同一引擎、同一语义，只是书写语言不同。

### 3.1 ⚠️ 德文标识符避开 `ue`/`ae`/`oe` 二合字母

canonicalizer 会把标识符里的 `ue`→`ü`、`ae`→`ä`、`oe`→`ö`。例如源码写 `fruehereSchaeden`，
引擎内部归一成 `frühereSchäden`——若你的输入 context 用的键是 `fruehereSchaeden`，字段访问会得到
`undefined`。规避：德文标识符用**不含二合字母**的命名（如本例用 `anzahlVorschaden` 而非 `fruehereSchaeden`）。
关键词（如 `hoechstens`/`gib zurueck`）不受影响——它们由词法包映射。

### 3.2 ⚠️ 非英文（zh/de）当前限制：标识符不能与关键词词同形

实测发现：在 zh / de 等非英文词法包下，**如果用户标识符（字段名/变量名/参数名）恰好是某个 CNL 关键词的词**，
编译会失败（典型报 `Expected '.'`）。

例：zh 词法把 `结果` 注册为关键词（`RESULT_OF`）。若你把一个字段也命名为 `结果`，翻译层会把它当关键词
拆成多 token（`result of`），破坏解析——**单个 `定义` 就会触发，与声明个数无关**。

```aster
# ❌ zh：字段名 结果 与关键词同形 → Expected '.'
定义 核定 包含 结果。

# ✅ zh：换一个不与关键词同形的字段名
定义 核定 包含 裁定结果。
```

英文词法**不受影响**（EN parser 能在标识符上下文容忍关键词词，如字段名 `given` 可用）；问题出在非英文的
关键词翻译层缺少"标识符位置不翻译"的上下文消歧。

**影响**：迁移 zh/de 规则时，避免让标识符与该语言的 CNL 关键词词撞名。
**规避**：给字段/变量/参数取**不与关键词同形**的名字（加前后缀即可，如 `裁定结果` 而非 `结果`）。
**状态**：已知引擎缺陷（关键词/标识符消歧），修复需双引擎对齐 + 发布级联。

---

## 4. S2：走治理生命周期

迁移**不是写完规则就上线**。Aster 的核心价值在于把每条规则推过一个可审计的生命周期：

```
起草(draft) → 提交审批(submit) → 审批通过(approve) → 版本固化 → 设为默认版本(set-default)
```

执行决策时锚定的是**某个已审批的版本**，这正是"证明线上跑的 = 已审批的那一版"的机制基础。

**当前 REST 暴露面（诚实声明）**：本仓库 `src/main/java` 当前对外暴露的版本相关 REST 端点只有：

- `GET /api/v1/policies/{policyId}/versions` — 查询版本历史
- `POST /api/v1/policies/{policyId}/rollback` — 回滚

`submit / approve / reject / activate(set-default) / deprecate / archive` 这些生命周期流转**目前是内部
service 能力（`PolicyVersionService`），尚未全部 API 化为公开 REST 端点**。迁移评估时若你的审批流程需要
通过 API 驱动这些流转，请先与维护方确认当前可用的接口（或通过 aster-cloud 托管面驱动），不要假设上面
全套 REST 端点已就绪。

把规则的审批流程接进你现有的变更管理（谁能审批、留痕、回滚），是 S2 的主要组织成本。

---

## 5. S3：接 API 执行

两条风险等级不同的执行路径（端点以 `PolicyEvaluationResource`、`@Path("/api/v1/policies")` 实际暴露为准）：

| 路径 | 端点 | 说明 |
|---|---|---|
| **已部署规则（推荐 GA 路径）** | `POST /api/v1/policies/evaluate` | 跑已部署的规则。请求体 `policyModule` + `policyFunction` + `context`。这是窄 SKU GA 推荐的对外执行面。 |
| **动态编译源码** | `POST /api/v1/policies/evaluate-source` | 直接编译并执行源码字符串。风险更高（容量 + 语义），受 HMAC/trial guard/限流保护，**不建议对外直接开放**。 |

最小请求示例（已部署规则）：

```bash
# 注意：/evaluate 的 context 是【位置参数数组】(Object[])，按规则形参顺序排列。
# 本规则 `decide given claim` 只有一个参数，故 context 是单元素数组。
curl -X POST https://<host>/api/v1/policies/evaluate \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <api-key>" \
  -d '{
    "policyModule": "aster.insurance.claim_adjudication",
    "policyFunction": "decide",
    "context": [
      { "claimantAge": 40, "policyActiveDays": 400, "claimAmount": 9000, "deductible": 500, "priorClaims": 2, "documentationComplete": true }
    ]
  }'
```

### 5.1 决策回放

**当前事实（避免误导）**：`POST /api/v1/policies/evaluate-source?trace=true` 会在响应里包一个
`DecisionTrace`（含 `finalResult` + `executionTimeMs`），但**逐步 `steps` 当前为空数组**（`List.of()`）——
"逐步表达式 + 每步命中分支"的细粒度回放是**产品路线目标，尚未在该端点实现**。已部署规则的 `/evaluate`
目前**不接受 `trace` 查询参数**。

决策回放是 Aster 的核心定位（"调出当时的规则和数据，重算出一样的结果"），其机制基础是
**规则版本固化 + 输入留痕**——同一规则版本 + 同一输入可独立重演出相同决策。但**细粒度逐步 trace 的
完整落地以本仓库实际端点能力为准**，迁移评估时请按上面的"当前事实"判断，别按理想态承诺。

### 5.2 集成形态

- **后端**：Quarkus/Java，可编译成 GraalVM 原生镜像。
- **浏览器内即时验证**：用 `@aster-cloud/aster-lang-ts` npm 引擎，与后端 Java 引擎共享同一套 CNL 语义，
  可在客户面前即时跑规则不依赖网络。（引擎层对 Stable 集的逐字节等价由项目的 parity gate 持续把守，
  是引擎质量承诺；本指南样本未独立复跑该对拍。）
- **最省心**：直接用 aster-cloud 托管 SaaS，绕开自建生态的部署/运维成本。

---

## 6. S4：灰度并行验证

迁移期把 Aster 与旧引擎**并行运行**，对同一批输入比对决策一致性：

1. 用决策一致性比对建立信任（Aster 引擎层对 Stable 集有持续的双引擎 parity gate 作为质量承诺）；
2. 对差异逐条核对（往往是旧引擎的隐含行为没写进规则）；
3. 一致性达标后再切流量。

---

## 7. 本地自验：怎么确认一条规则真能双引擎编译执行

迁移每条规则后，建议两端都验：

**TypeScript 引擎（parse + lower + eval，最完整）**：

```js
import { compile, evaluate, EN_US, ZH_CN, DE_DE } from '@aster-cloud/aster-lang-ts/browser';
const res = compile(source, { lexicon: EN_US });   // 非英文传 ZH_CN / DE_DE
if (!res.success) console.error(res.parseErrors);
// evaluate 第三参是入口函数的输入 context（键名=规则形参名）。
const out = evaluate(res.core, 'decide', { claim: { /* ...字段... */ } });
```

> 说明：`evaluate()`（TS 引擎本地自验）的 context 用**命名 Map**（键=形参名）；而生产 REST `/evaluate`
> 端点的 context 是**位置数组**（见 §5）。两者形态不同，别混用。

**Java/ANTLR 引擎（parse，确认 Java 语法接受）**：
用 aster-lang-core 的 `JavaParseHelper`（dual-engine 测试包），从 stdin 读 `.aster` 文件路径，输出 `OK`/`FAIL`。

> 注意：aster-lang-core 的 ANTLR 运行时是 **4.13.1**，跑 `JavaParseHelper` 时 classpath 须 pin 4.13.x
> 运行时 jar（gradle 缓存里同时存在 4.7.2，混用会抛 `version ... does not match` 初始化错）。

---

## 8. 一页速查

- **要不要迁**：仅当监管逼你"任意历史决策可解释 + 证明用的是已审批规则"时才迁。
- **写规则**：只用 Stable 子集；结构体构造用 `Type with f set to expr and ...`（不是 `Type(args)`）。
- **多语言**：关键词 + 标识符可本地化，决策逐字段一致；de 标识符避开 `ue/ae/oe`；zh 暂不支持两个 `定义`。
- **治理**：起草 → 审批 → 固化 → 设默认，是价值所在也是组织成本。
- **执行**：`POST /api/v1/policies/evaluate`（已部署规则，`policyModule`/`policyFunction`/`context`）；
  动态源码走 `/evaluate-source`（不建议对外）。逐步 trace 当前 `steps` 为空（路线目标）。先窄场景 PoC，别全量迁。
