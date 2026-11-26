package vn.ketoan.domain.report;

import java.util.Objects;
import vn.ketoan.domain.value.Money;

public record CashFlowSplit(CashFlowActivity activity, CashFlowDirection direction, Money amount) {
    public CashFlowSplit {
        Objects.requireNonNull(activity, "Cash flow activity is required");
        Objects.requireNonNull(direction, "Cash flow direction is required");
        Objects.requireNonNull(amount, "Cash flow amount is required");
        if (!amount.isPositive()) { throw new IllegalArgumentException("Cash flow split must be positive"); }
    }
}
