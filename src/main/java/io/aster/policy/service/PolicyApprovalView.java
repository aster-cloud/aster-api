package io.aster.policy.service;

import java.util.List;

/**
 * 审批视图（ADR 0022 §11.5 H4，Codex 复核）。
 *
 * <p>用户自定义别名引入"审批者看到的别名源码 vs 引擎实际执行的归一语义"的鸿沟，是社会工程
 * 攻击面（如 {@code approve as} 别名到 RETURN）。审批者**必须**同时看到两者才能有效审批。
 * 本 DTO 装配审批所需的全部材料：
 * <ul>
 *   <li>{@code aliasSource} —— 用户写的、带别名的源码（审批者第一眼看到的）</li>
 *   <li>{@code canonicalSource} —— 引擎 canonicalize 后的规范源码（别名归一成规范拼写后的真实形态）</li>
 *   <li>{@code aliasLegend} —— 别名→规范关键词的逐条对照（"你写的 X 实际是关键词 Y"）</li>
 *   <li>{@code irSummary} —— Core IR 摘要（最终执行语义的结构概览）</li>
 * </ul>
 *
 * <p>端点 wiring（把本视图暴露到审批 UI）在策略提交/审批的生产路径上接入（跨仓）；本 DTO +
 * {@link PolicyApprovalViewService} 是其数据装配，本仓可闭环。
 */
public record PolicyApprovalView(
    String aliasSource,
    String canonicalSource,
    List<AliasLegendEntry> aliasLegend,
    String irSummary
) {
    /** 别名对照条目：别名 → 它归一到的规范关键词（+ 语义 kind）。 */
    public record AliasLegendEntry(String alias, String canonicalKeyword, String kind) {}
}
