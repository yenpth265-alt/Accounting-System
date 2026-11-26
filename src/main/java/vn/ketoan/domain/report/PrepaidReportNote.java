package vn.ketoan.domain.report;

import java.util.Objects;
import java.util.UUID;
import vn.ketoan.domain.value.Money;

public record PrepaidReportNote(UUID planId, String reference, Money original, Money recognizedInPeriod,
                                Money totalRecognized, Money remainingAsset, boolean cancelled) {
    public PrepaidReportNote {
        Objects.requireNonNull(planId);
        reference = ReportText.required(reference, 32, "Prepaid reference");
        Objects.requireNonNull(recognizedInPeriod);
        if (!Objects.requireNonNull(original).isPositive() || Objects.requireNonNull(totalRecognized).isNegative()
                || Objects.requireNonNull(remainingAsset).isNegative()) { throw new IllegalArgumentException("Invalid prepaid note"); }
    }
}
