package vn.ketoan.domain.report;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import vn.ketoan.domain.value.Money;

public record CashFlowLine(UUID entryId, String reference, LocalDate postingDate, CashFlowActivity activity,
                           CashFlowDirection direction, Money amount, boolean inheritedReversal) {
    public CashFlowLine {
        Objects.requireNonNull(entryId);
        reference = ReportText.required(reference, 32, "Cash flow reference");
        ReportPeriod.requireDate(postingDate);
        new CashFlowSplit(activity, direction, amount);
    }
    public Money signedAmount() { return direction == CashFlowDirection.INFLOW ? amount : amount.negate(); }
}
