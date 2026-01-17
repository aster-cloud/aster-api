package io.aster.audit.chain;

import io.aster.policy.entity.AuditLog;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.commons.codec.digest.DigestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 审计哈希链验证服务（Phase 0 Task 3.3）
 *
 * 提供审计链完整性验证功能，检测以下问题：
 * - prev_hash 不匹配：断链（记录被删除或插入）
 * - current_hash 不匹配：篡改（字段被修改）
 *
 * 验证策略：
 * 1. 批量查询审计记录（按时间范围 + 租户）
 * 2. 逐条验证 prev_hash 链接和 current_hash 计算
 * 3. 支持分页验证大量记录（默认 1000 条/页）
 */
@ApplicationScoped
public class AuditChainVerifier {

    private static final int DEFAULT_PAGE_SIZE = 1000;

    /**
     * 验证指定租户在指定时间范围内的审计哈希链完整性
     *
     * @param tenantId  租户 ID
     * @param startTime 开始时间（包含）
     * @param endTime   结束时间（包含）
     * @return 验证结果
     */
    @Transactional
    public ChainVerificationResult verifyChain(String tenantId, Instant startTime, Instant endTime) {
        List<AuditLog> logs = fetchAuditLogs(tenantId, startTime, endTime);
        return verifyChainInternal(logs);
    }

    /**
     * 分页验证审计哈希链（用于大量记录）
     *
     * @param tenantId  租户 ID
     * @param startTime 开始时间（包含）
     * @param endTime   结束时间（包含）
     * @param pageSize  每页记录数
     * @return 验证结果
     */
    @Transactional
    public ChainVerificationResult verifyChainPaginated(
        String tenantId,
        Instant startTime,
        Instant endTime,
        int pageSize
    ) {
        int page = 0;
        int totalVerified = 0;
        String expectedPrevHash = null;

        while (true) {
            List<AuditLog> logs = fetchAuditLogsPage(tenantId, startTime, endTime, page, pageSize);
            if (logs.isEmpty()) {
                break;
            }

            for (AuditLog log : logs) {
                // 跳过没有哈希值的旧记录
                if (log.currentHash == null) {
                    continue;
                }

                // 检查链接
                if (!Objects.equals(log.prevHash, expectedPrevHash)) {
                    String message = String.format(
                        "prev_hash mismatch at record id=%d: expected=%s, got=%s",
                        log.id,
                        expectedPrevHash != null ? expectedPrevHash.substring(0, 8) + "..." : "null",
                        log.prevHash != null ? log.prevHash.substring(0, 8) + "..." : "null"
                    );
                    return ChainVerificationResult.invalid(log.timestamp, message, totalVerified);
                }

                String computedHash = computeHash(log);
                if (!computedHash.equals(log.currentHash)) {
                    String message = String.format(
                        "current_hash tampered at record id=%d",
                        log.id
                    );
                    return ChainVerificationResult.invalid(log.timestamp, message, totalVerified);
                }

                expectedPrevHash = log.currentHash;
                totalVerified++;
            }

            page++;
        }

        return ChainVerificationResult.valid(totalVerified);
    }

    /**
     * 查询审计日志
     */
    private List<AuditLog> fetchAuditLogs(String tenantId, Instant startTime, Instant endTime) {
        return AuditLog.find(
            "tenantId = ?1 AND timestamp >= ?2 AND timestamp <= ?3 ORDER BY timestamp ASC, id ASC",
            tenantId, startTime, endTime
        ).list();
    }

    /**
     * 分页查询审计日志
     */
    private List<AuditLog> fetchAuditLogsPage(
        String tenantId,
        Instant startTime,
        Instant endTime,
        int page,
        int pageSize
    ) {
        return AuditLog.find(
            "tenantId = ?1 AND timestamp >= ?2 AND timestamp <= ?3 ORDER BY timestamp ASC, id ASC",
            tenantId, startTime, endTime
        ).page(page, pageSize).list();
    }

    /**
     * 验证哈希链内部逻辑
     */
    private ChainVerificationResult verifyChainInternal(List<AuditLog> logs) {
        if (logs.isEmpty()) {
            return ChainVerificationResult.valid(0);
        }

        String expectedPrevHash = null;
        int recordsVerified = 0;

        for (AuditLog log : logs) {
            // 跳过没有哈希值的旧记录（向后兼容）
            if (log.currentHash == null) {
                Log.debugf("Skipping legacy record without hash: id=%d, timestamp=%s", log.id, log.timestamp);
                continue;
            }

            // 检查 prev_hash 链接
            if (!Objects.equals(log.prevHash, expectedPrevHash)) {
                String message = String.format(
                    "prev_hash mismatch at record id=%d: expected=%s, got=%s (chain broken)",
                    log.id,
                    expectedPrevHash != null ? expectedPrevHash.substring(0, 8) + "..." : "null",
                    log.prevHash != null ? log.prevHash.substring(0, 8) + "..." : "null"
                );
                Log.warnf(message);
                return ChainVerificationResult.invalid(log.timestamp, message, recordsVerified);
            }

            // 检查 current_hash 计算
            String computedHash = computeHash(log);
            if (!computedHash.equals(log.currentHash)) {
                String message = String.format(
                    "current_hash tampered at record id=%d: expected=%s, got=%s (record modified)",
                    log.id,
                    computedHash.substring(0, 8) + "...",
                    log.currentHash.substring(0, 8) + "..."
                );
                Log.warnf(message);
                return ChainVerificationResult.invalid(log.timestamp, message, recordsVerified);
            }

            expectedPrevHash = log.currentHash;
            recordsVerified++;
        }

        Log.debugf("Chain verification succeeded: tenant=%s, recordsVerified=%d", logs.get(0).tenantId, recordsVerified);
        return ChainVerificationResult.valid(recordsVerified);
    }

    /**
     * 计算审计记录的哈希值（必须与 AuditEventListener.computeHashChain 保持一致）
     */
    private String computeHash(AuditLog log) {
        StringBuilder content = new StringBuilder();
        if (log.prevHash != null) {
            content.append(log.prevHash);
        }
        content.append(log.eventType != null ? log.eventType : "");
        content.append(log.timestamp != null ? log.timestamp.toString() : "");
        content.append(log.tenantId != null ? log.tenantId : "");
        content.append(log.policyModule != null ? log.policyModule : "");
        content.append(log.policyFunction != null ? log.policyFunction : "");
        content.append(log.success != null ? log.success.toString() : "");

        return DigestUtils.sha256Hex(content.toString());
    }
}
