package vn.ketoan.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

public record PrepaidAllocationRecord(UUID id, UUID planId, YearMonth month, UUID targetDocumentId,
                                      long createdBy, Instant createdAt) {

    public PrepaidAllocationRecord {
        Objects.requireNonNull(id, "Prepaid allocation id is required");
        Objects.requireNonNull(planId, "Prepaid plan id is required");
        Objects.requireNonNull(month, "Prepaid allocation month is required");
        Objects.requireNonNull(targetDocumentId, "Prepaid allocation document id is required");
        Objects.requireNonNull(createdAt, "Prepaid allocation creation time is required");
        if (createdBy <= 0 || month.getYear() < 1000 || month.getYear() > 9999) {
            throw new IllegalArgumentException("Prepaid allocation requires a valid actor and month");
        }
    }
}
