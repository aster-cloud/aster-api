package io.aster.audit;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * PolicyAnalyticsResourceTest 专用 Profile。
 *
 * 切换到 PostgreSQL/生产 Flyway 脚本，确保 Testcontainers 环境下与生产 schema 保持一致。
 */
public class PostgresAnalyticsTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
            Map.entry("quarkus.datasource.db-kind", "postgresql"),
            Map.entry("quarkus.flyway.locations", "classpath:db/migration"),
            Map.entry("quarkus.flyway.baseline-on-migrate", "true"),
            Map.entry("quarkus.flyway.baseline-version", "0"),
            Map.entry("quarkus.flyway.clean-at-start", "true")
        );
    }
}
