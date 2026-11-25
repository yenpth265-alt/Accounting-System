package vn.ketoan.domain.report;

import java.time.LocalDate;
import java.util.List;
import vn.ketoan.domain.value.Money;

/** A point-in-time financial position; contra balances retain their signs. */
public record BalanceSheet(LocalDate asOf, boolean periodsClosed, List<BalanceSheetLine> rows) {
    public BalanceSheet {
        ReportPeriod.requireDate(asOf);
        rows = List.copyOf(rows);
        Money assets = total(rows, BalanceSheetSection.ASSET);
        Money financing = total(rows, BalanceSheetSection.LIABILITY).add(total(rows, BalanceSheetSection.EQUITY));
        if (!assets.equals(financing)) { throw new IllegalArgumentException("Balance sheet assets must equal liabilities plus equity"); }
    }
    public Money assets() { return total(rows, BalanceSheetSection.ASSET); }
    public Money liabilities() { return total(rows, BalanceSheetSection.LIABILITY); }
    public Money equity() { return total(rows, BalanceSheetSection.EQUITY); }
    private static Money total(List<BalanceSheetLine> rows, BalanceSheetSection section) {
        return rows.stream().filter(row -> row.section() == section).map(BalanceSheetLine::amount).reduce(Money.ZERO, Money::add);
    }
}
