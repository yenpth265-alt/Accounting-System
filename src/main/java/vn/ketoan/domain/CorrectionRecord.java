package vn.ketoan.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only explanation and source link. The target voucher retains the normal approval workflow. */
public record CorrectionRecord(UUID id, UUID sourceDocumentId, UUID targetDocumentId, CorrectionKind kind,
                                String reason, long createdBy, Instant createdAt) {

    public CorrectionRecord {
        Objects.requireNonNull(id, "Correction id is required");
        Objects.requireNonNull(sourceDocumentId, "Source document id is required");
        Objects.requireNonNull(targetDocumentId, "Target document id is required");
        Objects.requireNonNull(kind, "Correction kind is required");
        reason = DomainValidation.requiredText(reason, 255, "Correction reason");
        Objects.requireNonNull(createdAt, "Correction creation time is required");
        if (createdBy <= 0 || sourceDocumentId.equals(targetDocumentId)) {
            throw new IllegalArgumentException("Correction requires a positive actor id and a different source and target");
        }
    }
}
