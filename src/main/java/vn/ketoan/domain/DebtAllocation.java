package vn.ketoan.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only allocation with the trusted actor and recording time. */
public record DebtAllocation(AllocationRequest request, long recordedBy, Instant recordedAt) {

    public DebtAllocation {
        Objects.requireNonNull(request, "Allocation request is required");
        Objects.requireNonNull(recordedAt, "Allocation recording time is required");
        if (recordedBy <= 0) {
            throw new IllegalArgumentException("Allocation actor id must be positive");
        }
    }

    public UUID id() {
        return request.id();
    }
}
