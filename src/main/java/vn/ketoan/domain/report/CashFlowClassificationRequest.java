package vn.ketoan.domain.report;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.ketoan.domain.value.Money;

public record CashFlowClassificationRequest(UUID id, UUID entryId, int expectedRevision, String reason,
                                            Money internalTransfer, List<CashFlowSplit> splits) {
    public CashFlowClassificationRequest {
        Objects.requireNonNull(id, "Classification request id is required");
        Objects.requireNonNull(entryId, "Journal entry id is required");
        if (expectedRevision < 0) { throw new IllegalArgumentException("Expected classification revision cannot be negative"); }
        reason = ReportText.required(reason, 255, "Classification reason");
        splits = List.copyOf(splits);
        CashFlowClassification.requireAmounts(internalTransfer, splits);
    }
}
