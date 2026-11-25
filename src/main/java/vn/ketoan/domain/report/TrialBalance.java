package vn.ketoan.domain.report;

import java.util.List;
import java.util.Objects;

public record TrialBalance(ReportPeriod period, boolean periodsClosed, List<TrialBalanceRow> rows) {
    public TrialBalance {
        Objects.requireNonNull(period, "Trial balance period is required");
        rows = List.copyOf(rows);
        if (!rows.stream().map(TrialBalanceRow::opening).reduce(DebitCredit.ZERO, DebitCredit::add).balanced()
                || !rows.stream().map(TrialBalanceRow::movements).reduce(DebitCredit.ZERO, DebitCredit::add).balanced()
                || !rows.stream().map(TrialBalanceRow::closing).reduce(DebitCredit.ZERO, DebitCredit::add).balanced()) {
            throw new IllegalArgumentException("Trial balance debit and credit columns must balance");
        }
    }
    public DebitCredit openingTotals() { return rows.stream().map(TrialBalanceRow::opening).reduce(DebitCredit.ZERO, DebitCredit::add); }
    public DebitCredit movementTotals() { return rows.stream().map(TrialBalanceRow::movements).reduce(DebitCredit.ZERO, DebitCredit::add); }
    public DebitCredit closingTotals() { return rows.stream().map(TrialBalanceRow::closing).reduce(DebitCredit.ZERO, DebitCredit::add); }
}
