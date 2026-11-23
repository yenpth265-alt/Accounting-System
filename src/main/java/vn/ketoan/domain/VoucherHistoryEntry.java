package vn.ketoan.domain;

import java.time.Instant;
import java.util.Objects;

public record VoucherHistoryEntry(long version, VoucherAction action, long actorId,
                                  VoucherStatus fromStatus, VoucherStatus toStatus,
                                  Instant occurredAt, String note, VoucherSnapshot content) {

    public VoucherHistoryEntry {
        if (version < 0 || actorId <= 0) {
            throw new IllegalArgumentException("History requires a nonnegative version and positive actor id");
        }
        Objects.requireNonNull(action, "Action is required");
        Objects.requireNonNull(toStatus, "Destination status is required");
        Objects.requireNonNull(occurredAt, "History time is required");
        Objects.requireNonNull(content, "Content snapshot is required");
        note = note == null ? "" : note.strip();
        if (note.length() > 255) {
            throw new IllegalArgumentException("History note must not exceed 255 characters");
        }
        if (action == VoucherAction.RETURNED || action == VoucherAction.APPROVAL_WITHDRAWN
                || action == VoucherAction.CANCELLED) {
            note = DomainValidation.requiredText(note, 255, "Action reason");
        }
        if ((action == VoucherAction.CREATED) != (fromStatus == null)) {
            throw new IllegalArgumentException("Only creation can have no previous status");
        }
    }
}
