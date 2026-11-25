package vn.ketoan.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClosingEvent(UUID batchId, ClosingAction action, String reason, long actorId, Instant occurredAt) {
    public ClosingEvent {
        Objects.requireNonNull(batchId, "Closing batch id is required");
        Objects.requireNonNull(action, "Closing action is required");
        reason = DomainValidation.requiredText(reason, 255, "Closing reason");
        Objects.requireNonNull(occurredAt, "Closing event time is required");
        if (actorId <= 0) { throw new IllegalArgumentException("Closing actor must be positive"); }
    }
}
