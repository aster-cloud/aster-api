# 技术债全景报告 (2026-06-10)

## 核心结论
代码库整体健康。原始 TODO/FIXME 信号 (ts 110/core 18/cloud 18) 经实证绝大多数是误报：
i18n 模板占位、功能常量、测试 fixture、有意保留的 ADR/迁移标记。

## 质量门实测
| 仓 | typecheck | lint/编译警告 |
|---|---|---|
| aster-lang-ts | 0 error | 0 error + 2 warning(已修) |
| aster-cloud | 0 error | 0 problem |
| aster-lang-core | — | 0 警告 |
| aster-lang-truffle | — | 1 deprecation(框架API,有风险,未动) |
| aster-api | — | 2 [try]误报(已抑制) |

## 已解决 (ts#18 + api#34)
- ts: 删 2 个 unused eslint-disable directive
- ts: 空 TODO(package-installer lockfile) → 真实 console.warn
- api: 2 个 [try] 误报警告 → @SuppressWarnings("try")

## 有意保留 (非债,未动)
- 96× "TODO: 翻译" @ lexicons/template.ts — i18n 模板占位(功能性)
- TODO_TRANSLATE_ 前缀 @ core — 未翻译标记机制
- @deprecated ADR 引用 — 向后兼容迁移标记
- 测试 fixture 假数据 ('TODO'/'XXX'/'-1234567890abcdef')

## 需评估 (有风险,留待单独决策)
- aster-lang-truffle AsterLanguage.java:41 — getCurrentContext deprecated
  → 需迁移新 Truffle API,涉框架版本,有行为风险,不在安全清理范围
