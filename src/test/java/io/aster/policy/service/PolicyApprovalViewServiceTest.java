package io.aster.policy.service;

import io.aster.policy.entity.PolicyVersion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批视图装配测试（ADR 0022 §11.5 H4）：审批者须同时看到别名源码 + 规范源码 + 别名对照。
 */
@QuarkusTest
class PolicyApprovalViewServiceTest {

    @Inject
    PolicyApprovalViewService service;

    @Test
    void buildsLegendAndCanonicalSourceForAliasedVersion() {
        PolicyVersion v = new PolicyVersion();
        v.content = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 3.";
        v.locale = "en-US";
        v.aliasSet = "{\"TIMES\":[\"multiplied by\"]}";

        PolicyApprovalView view = service.build(v);

        // 别名源码=用户写的原文
        assertEquals(v.content, view.aliasSource());
        // 规范源码：别名 multiplied by 已归一（不再含别名拼写，含规范运算符形态）
        assertNotNull(view.canonicalSource());
        assertTrue(view.canonicalSource().contains("*") || !view.canonicalSource().contains("multiplied by"),
            "规范源码应已把 'multiplied by' 归一");
        // 别名对照：multiplied by → TIMES 的规范关键词
        assertEquals(1, view.aliasLegend().size());
        PolicyApprovalView.AliasLegendEntry e = view.aliasLegend().get(0);
        assertEquals("multiplied by", e.alias());
        assertEquals("TIMES", e.kind());
        assertEquals("times", e.canonicalKeyword(), "TIMES 规范关键词应是 times");
        // IR 摘要存在
        assertNotNull(view.irSummary());
        assertTrue(view.irSummary().contains("Module") || view.irSummary().contains("\"*\""));
    }

    @Test
    void emptyLegendForNoAliasVersion() {
        PolicyVersion v = new PolicyVersion();
        v.content = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
        v.locale = "en-US";
        v.aliasSet = null;

        PolicyApprovalView view = service.build(v);
        assertTrue(view.aliasLegend().isEmpty(), "无别名 → 空对照");
        assertNotNull(view.canonicalSource());
        assertTrue(view.warnings().isEmpty(), "正常版本无告警");
    }

    @Test
    void corruptAliasSetSurfacesWarningNotSilentlySwallowed() {
        // H4：损坏 aliasSet 不静默隐藏——审批者必须看到告警
        PolicyVersion v = new PolicyVersion();
        v.content = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
        v.locale = "en-US";
        v.aliasSet = "{not valid json";

        PolicyApprovalView view = service.build(v);
        assertTrue(view.warnings().stream().anyMatch(w -> w.contains("无法解析")),
            "损坏 aliasSet 应在 warnings 中高亮");
    }
}
