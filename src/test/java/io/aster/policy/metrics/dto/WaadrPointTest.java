package io.aster.policy.metrics.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WaadrPoint record 形态测试
 *
 * 物化视图查询的 SQL/JPA 集成测试需要 Postgres，放在 QuarkusTest；
 * 这里只验证 record 字段映射。
 */
class WaadrPointTest {

    @Test
    void preservesAllFields() {
        Instant week = Instant.parse("2026-05-04T00:00:00Z");
        WaadrPoint p = new WaadrPoint(week, "tenant-1", "business_expert", 42L);
        assertEquals(week, p.week());
        assertEquals("tenant-1", p.tenantId());
        assertEquals("business_expert", p.authorRole());
        assertEquals(42L, p.waadr());
    }
}
