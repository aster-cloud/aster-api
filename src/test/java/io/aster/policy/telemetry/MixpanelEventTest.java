package io.aster.policy.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MixpanelEvent record 基础测试
 *
 * 真实 MixpanelClient 涉及 Vert.x WebClient + Caffeine + Counter，集成测试更合适；
 * 这里只验证 record 形态与基本字段。
 */
class MixpanelEventTest {

    @Test
    void preservesAllFields() {
        MixpanelEvent ev = new MixpanelEvent("draft_published", "user-1", Map.of("rule_id", "p1"));
        assertEquals("draft_published", ev.event());
        assertEquals("user-1", ev.distinctId());
        assertEquals("p1", ev.properties().get("rule_id"));
    }

    @Test
    void allowsEmptyProperties() {
        MixpanelEvent ev = new MixpanelEvent("ping", "user-1", Map.of());
        assertNotNull(ev.properties());
        assertEquals(0, ev.properties().size());
    }
}
