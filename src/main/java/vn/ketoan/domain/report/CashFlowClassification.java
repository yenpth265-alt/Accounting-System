package vn.ketoan.domain.report;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.ketoan.domain.value.Money;

/** Append-only revisions classify cash movements without rewriting the journal. */
public record CashFlowClassification(UUID id, UUID entryId, int revision, String reason,
                                     Money internalTransfer, List<CashFlowSplit> splits,
                                     long recordedBy, Instant recordedAt) {
    public CashFlowClassification {
        Objects.requireNonNull(id, "Classification id is required");
        Objects.requireNonNull(entryId, "Journal entry id is required");
        reason = ReportText.required(reason, 255, "Classification reason");
        Objects.requireNonNull(recordedAt, "Classification time is required");
        if (revision < 1 || recordedBy <= 0) { throw new IllegalArgumentException("Classification revision and actor must be positive"); }
        splits = List.copyOf(splits);
        requireAmounts(internalTransfer, splits);
    }
    static void requireAmounts(Money internal, List<CashFlowSplit> splits) {
        Objects.requireNonNull(internal, "Internal transfer is required");
        if (internal.isNegative() || internal.toBigDecimal().precision() > 38 || splits.size() > 6) {
            throw new IllegalArgumentException("Invalid cash flow classification amounts");
        }
        var keys = new HashSet<String>();
        for (CashFlowSplit split : splits) {
            if (split.activity() == CashFlowActivity.UNCLASSIFIED || split.amount().toBigDecimal().precision() > 38
                    || !keys.add(split.activity() + ":" + split.direction())) {
                throw new IllegalArgumentException("Classify each activity/direction at most once with a supported amount");
            }
        }
    }
}
