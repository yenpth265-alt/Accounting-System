package vn.ketoan.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Full cancellation recorded separately; the original allocation is never overwritten. */
public record AllocationCancellation(UUID allocationId, LocalDate effectiveDate, String reason,
                                     long recordedBy, Instant recordedAt) {

    public AllocationCancellation {
        Objects.requireNonNull(allocationId, "Allocation id is required");
        effectiveDate = DomainValidation.date(effectiveDate, "Cancellation date");
        reason = DomainValidation.requiredText(reason, 255, "Cancellation reason");
        Objects.requireNonNull(recordedAt, "Cancellation recording time is required");
        if (recordedBy <= 0) {
            throw new IllegalArgumentException("Cancellation actor id must be positive");
        }
    }
}
