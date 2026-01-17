package io.aster.workflow;

/**
 * 崩溃恢复测试配置
 *
 * 继承 TimerIntegrationTestProfile 以复用:
 * - PostgresTestResource (Testcontainers)
 * - Quarkus Scheduler 启用
 * - DEBUG 日志级别
 */
public class CrashRecoveryTestProfile extends TimerIntegrationTestProfile {
    // 无需额外配置，直接继承
}
