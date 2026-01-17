package io.aster.audit.inbox;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.SQLException;

/**
 * Inbox 事件实体
 * 用于 Inbox 模式的幂等性去重，防止重复处理同一事件
 */
@RegisterForReflection
@Entity
@Table(name = "inbox_events")
public class InboxEvent extends PanacheEntityBase {

    @Id
    @Column(name = "idempotency_key", length = 255, nullable = false)
    public String idempotencyKey;

    @Column(name = "event_type", length = 100, nullable = false)
    public String eventType;

    @Column(name = "tenant_id", length = 100, nullable = false)
    public String tenantId;

    @Column(name = "processed_at", nullable = false)
    public Instant processedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "JSONB")
    public String payload;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * 尝试插入事件（Inbox 模式核心方法）
     *
     * @param key 幂等性键
     * @param eventType 事件类型
     * @param tenantId 租户ID
     * @return 成功返回插入的实体，重复返回 null
     */
    public static Uni<InboxEvent> tryInsert(String key, String eventType, String tenantId) {
        return tryInsert(key, eventType, tenantId, null);
    }

    /**
     * 尝试插入事件（带 payload）
     *
     * @param key 幂等性键
     * @param eventType 事件类型
     * @param tenantId 租户ID
     * @param payload 可选的事件 payload（JSON 字符串）
     * @return 成功返回插入的实体，重复返回 null
     */
    public static Uni<InboxEvent> tryInsert(String key, String eventType, String tenantId, String payload) {
        return Panache.withTransaction(() -> persistEvent(key, eventType, tenantId, payload));
    }

    private static Uni<InboxEvent> persistEvent(String key, String eventType, String tenantId, String payload) {
        var event = new InboxEvent();
        event.idempotencyKey = key;
        event.eventType = eventType;
        event.tenantId = tenantId;
        event.processedAt = Instant.now();
        event.createdAt = Instant.now();
        event.payload = payload;

        return Panache.getSession()
            .onItem().transformToUni(session ->
                session.createNativeQuery(
                        "INSERT INTO inbox_events (idempotency_key, event_type, tenant_id, processed_at, created_at, payload) " +
                            "VALUES (:key, :eventType, :tenantId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CAST(:payload AS JSONB)) " +
                            "ON CONFLICT (idempotency_key) DO NOTHING")
                    .setParameter("key", key)
                    .setParameter("eventType", eventType)
                    .setParameter("tenantId", tenantId)
                    .setParameter("payload", payload)
                    .executeUpdate()
                    .map(inserted -> inserted > 0 ? event : null)
            );
    }

    /**
     * 清理旧事件（TTL 机制）
     *
     * @param daysToKeep 保留天数（默认 7 天）
     * @return 删除的记录数
     */
    public static Uni<Long> cleanupOldEvents(int daysToKeep) {
        Instant cutoff = Instant.now().minus(daysToKeep, ChronoUnit.DAYS);
        return delete("processedAt < :cutoff", Parameters.with("cutoff", cutoff));
    }

    /**
     * 检查是否存在指定幂等性键
     *
     * @param key 幂等性键
     * @return 存在返回 true，否则返回 false
     */
    public static Uni<Boolean> exists(String key) {
        return find("idempotencyKey", key)
            .count()
            .map(count -> count != null && count > 0);
    }

    /**
     * 判断异常是否为 UNIQUE constraint violation
     */
    private static boolean isUniqueViolation(Throwable ex) {
        if (ex instanceof SQLException sqlException) {
            if ("23505".equals(sqlException.getSQLState())) {
                return true;
            }
            String message = sqlException.getMessage();
            return message != null && message.contains("inbox_events_pkey");
        }
        if (ex instanceof ConstraintViolationException violation) {
            return isInboxConstraint(violation);
        }
        if (ex instanceof PersistenceException persistenceException) {
            Throwable cause = persistenceException.getCause();
            if (cause instanceof ConstraintViolationException violation) {
                return isInboxConstraint(violation);
            }
            if (cause != null) {
                return isUniqueViolation(cause);
            }
        }
        return false;
    }

    private static boolean isInboxConstraint(ConstraintViolationException violation) {
        String constraintName = violation.getConstraintName();
        if (constraintName != null && constraintName.contains("inbox_events_pkey")) {
            return true;
        }
        String message = violation.getMessage();
        return message != null && message.contains("inbox_events_pkey");
    }
}
