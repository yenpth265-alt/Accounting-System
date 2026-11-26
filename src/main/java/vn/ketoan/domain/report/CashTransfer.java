package vn.ketoan.domain.report;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import vn.ketoan.domain.value.Money;

public record CashTransfer(UUID entryId, String reference, LocalDate postingDate, Money amount) {
    public CashTransfer {
        Objects.requireNonNull(entryId);
        reference = ReportText.required(reference, 32, "Transfer reference");
        ReportPeriod.requireDate(postingDate);
        if (!Objects.requireNonNull(amount).isPositive()) { throw new IllegalArgumentException("Internal transfer must be positive"); }
    }
}
