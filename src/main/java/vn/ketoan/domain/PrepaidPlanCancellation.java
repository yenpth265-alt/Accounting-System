package vn.ketoan.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record PrepaidPlanCancellation(UUID planId, LocalDate effectiveDate, String reason,
                                      long recordedBy, Instant recordedAt) {

    public PrepaidPlanCancellation {
        Objects.requireNonNull(planId, "Prepaid plan id is required");
        effectiveDate = DomainValidation.date(effectiveDate, "Plan cancellation date");
        reason = DomainValidation.requiredText(reason, 255, "Plan cancellation reason");
        Objects.requireNonNull(recordedAt, "Plan cancellation time is required");
        if (recordedBy <= 0) { throw new IllegalArgumentException("Plan cancellation actor id must be positive"); }
    }
}
