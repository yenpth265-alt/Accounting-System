package vn.ketoan.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.ketoan.domain.value.Money;

/** Immutable source reservation and ordered generated vouchers. Decisions are append-only. */
public record ClosingBatch(ClosingRequest request, List<UUID> sourceEntryIds, List<UUID> documentIds,
                           String sourceChecksum, Money revenue, Money expense, Money profit,
                           long preparedBy, Instant preparedAt) {
    public ClosingBatch {
        Objects.requireNonNull(request, "Closing request is required");
        sourceEntryIds = List.copyOf(sourceEntryIds);
        documentIds = List.copyOf(documentIds);
        new ClosingPreview(revenue, expense, profit, sourceChecksum, List.of());
        Objects.requireNonNull(preparedAt, "Preparation time is required");
        if (preparedBy <= 0 || documentIds.size() > 3
                || new HashSet<>(sourceEntryIds).size() != sourceEntryIds.size()
                || new HashSet<>(documentIds).size() != documentIds.size()) {
            throw new IllegalArgumentException("Closing actors or source/document ids are invalid");
        }
    }
    public UUID id() { return request.id(); }
}
