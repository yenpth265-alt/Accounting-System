package vn.ketoan.domain;

import java.util.Objects;
import java.util.UUID;

/** A balanced delta, linked to its posted source. Reuse id for retries of the same request. */
public record AdjustmentRequest(UUID id, UUID sourceDocumentId, String reason, Draft content) {

    public AdjustmentRequest {
        Objects.requireNonNull(id, "Adjustment request id is required");
        Objects.requireNonNull(sourceDocumentId, "Source document id is required");
        reason = DomainValidation.requiredText(reason, 255, "Adjustment reason");
        Objects.requireNonNull(content, "Adjustment content is required");
    }
}
