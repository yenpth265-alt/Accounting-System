package vn.ketoan.domain;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/** One plan covers the complete amount of one posted prepaid-asset debit line. */
public record PrepaidPlanRequest(UUID id, String reference, JournalLineKey source, String expenseAccountCode,
                                 YearMonth firstMonth, int months) {

    public PrepaidPlanRequest {
        Objects.requireNonNull(id, "Prepaid plan id is required");
        reference = DomainValidation.code(reference, 32, "Prepaid plan reference");
        Objects.requireNonNull(source, "Posted prepaid source line is required");
        expenseAccountCode = Account.normalizeCode(expenseAccountCode);
        Objects.requireNonNull(firstMonth, "First allocation month is required");
        if (months < 1 || months > 120 || firstMonth.getYear() < 1000 || firstMonth.getYear() > 9999
                || firstMonth.plusMonths(months - 1L).getYear() > 9999) {
            throw new IllegalArgumentException("Use 1 to 120 months within years 1000 to 9999");
        }
    }
}
