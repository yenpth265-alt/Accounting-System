package vn.ketoan.domain;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

public record ClosingRequest(UUID id, String reference, YearMonth month,
                             String resultAccountCode, String retainedEarningsAccountCode) {
    public ClosingRequest {
        Objects.requireNonNull(id, "Closing id is required");
        reference = DomainValidation.code(reference, 28, "Closing reference");
        Objects.requireNonNull(month, "Closing month is required");
        DomainValidation.date(month.atEndOfMonth(), "Closing month");
        resultAccountCode = Account.normalizeCode(resultAccountCode);
        retainedEarningsAccountCode = Account.normalizeCode(retainedEarningsAccountCode);
        if (resultAccountCode.equals(retainedEarningsAccountCode)) {
            throw new IllegalArgumentException("Result and retained earnings accounts must differ");
        }
    }
}
