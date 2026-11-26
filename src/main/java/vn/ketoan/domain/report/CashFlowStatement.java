package vn.ketoan.domain.report;

import java.util.List;
import java.util.Objects;
import vn.ketoan.domain.value.Money;

/** Direct cash flows. Internal transfers never contribute to external inflows or outflows. */
public record CashFlowStatement(ReportPeriod period, boolean periodsClosed, Money openingCash, Money closingCash,
                                List<CashFlowLine> rows, List<CashTransfer> internalTransfers) {
    public CashFlowStatement {
        Objects.requireNonNull(period);
        Objects.requireNonNull(openingCash);
        Objects.requireNonNull(closingCash);
        rows = List.copyOf(rows);
        internalTransfers = List.copyOf(internalTransfers);
        if (rows.stream().anyMatch(row -> !period.contains(row.postingDate()))
                || internalTransfers.stream().anyMatch(row -> !period.contains(row.postingDate()))
                || !openingCash.add(rows.stream().map(CashFlowLine::signedAmount).reduce(Money.ZERO, Money::add)).equals(closingCash)) {
            throw new IllegalArgumentException("Cash flow dates and opening/movement/closing cash must reconcile");
        }
    }
    public Money inflow(CashFlowActivity activity) { return sum(activity, CashFlowDirection.INFLOW); }
    public Money outflow(CashFlowActivity activity) { return sum(activity, CashFlowDirection.OUTFLOW); }
    public Money net(CashFlowActivity activity) { return inflow(activity).subtract(outflow(activity)); }
    public boolean complete() { return rows.stream().noneMatch(row -> row.activity() == CashFlowActivity.UNCLASSIFIED); }
    private Money sum(CashFlowActivity activity, CashFlowDirection direction) {
        return rows.stream().filter(row -> row.activity() == activity && row.direction() == direction).map(CashFlowLine::amount).reduce(Money.ZERO, Money::add);
    }
}
