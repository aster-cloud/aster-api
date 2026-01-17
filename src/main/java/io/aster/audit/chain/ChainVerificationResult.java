package io.aster.audit.chain;

import java.time.Instant;

/**
 * 审计哈希链验证结果
 *
 * 记录审计链验证的详细结果，包括验证是否通过、断链位置、篡改原因等。
 */
public class ChainVerificationResult {
    private final boolean valid;
    private final Instant brokenAt;
    private final String reason;
    private final int recordsVerified;

    private ChainVerificationResult(boolean valid, Instant brokenAt, String reason, int recordsVerified) {
        this.valid = valid;
        this.brokenAt = brokenAt;
        this.reason = reason;
        this.recordsVerified = recordsVerified;
    }

    /**
     * 创建验证通过的结果
     */
    public static ChainVerificationResult valid(int recordsVerified) {
        return new ChainVerificationResult(true, null, null, recordsVerified);
    }

    /**
     * 创建验证失败的结果
     */
    public static ChainVerificationResult invalid(Instant brokenAt, String reason, int recordsVerified) {
        return new ChainVerificationResult(false, brokenAt, reason, recordsVerified);
    }

    public boolean isValid() {
        return valid;
    }

    public Instant getBrokenAt() {
        return brokenAt;
    }

    public String getReason() {
        return reason;
    }

    public int getRecordsVerified() {
        return recordsVerified;
    }

    @Override
    public String toString() {
        if (valid) {
            return String.format("ChainVerificationResult{valid=true, recordsVerified=%d}", recordsVerified);
        } else {
            return String.format("ChainVerificationResult{valid=false, brokenAt=%s, reason='%s', recordsVerified=%d}",
                brokenAt, reason, recordsVerified);
        }
    }
}
