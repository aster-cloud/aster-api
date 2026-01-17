package io.aster.policy.api.testdata;

import io.aster.validation.constraints.NotEmpty;
import io.aster.validation.constraints.Range;
import jakarta.validation.constraints.NotNull;

/**
 * 测试用贷款申请 DTO，携带基础约束以验证 PolicyTypeConverter 的语义校验。
 */
public class LoanApplicationWithConstraints {
    @NotNull
    @NotEmpty
    private final String applicantId;

    @Range(min = 1000, max = 5_000_000)
    private final int amount;

    @Range(min = 12, max = 360)
    private final int termMonths;

    @NotNull
    @NotEmpty
    private final String purpose;

    public LoanApplicationWithConstraints(String applicantId, int amount, int termMonths, String purpose) {
        this.applicantId = applicantId;
        this.amount = amount;
        this.termMonths = termMonths;
        this.purpose = purpose;
    }

    public String getApplicantId() {
        return applicantId;
    }

    public int getAmount() {
        return amount;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public String getPurpose() {
        return purpose;
    }
}
