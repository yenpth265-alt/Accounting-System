package vn.ketoan.domain.report;

import java.util.List;
import java.util.Objects;
import vn.ketoan.domain.value.Money;

public record AccountLedger(LedgerQuery query, boolean periodsClosed, Money openingBalance, List<LedgerMovement> rows) {
    public AccountLedger {
        Objects.requireNonNull(query, "Ledger query is required");
        Objects.requireNonNull(openingBalance, "Opening balance is required");
        rows = List.copyOf(rows);
        Money current = openingBalance;
        for (LedgerMovement row : rows) {
            current = current.add(row.posting().net());
            if (!query.period().contains(row.posting().postingDate()) || !current.equals(row.runningBalance())) {
                throw new IllegalArgumentException("Ledger dates and running balances are inconsistent");
            }
        }
    }
    public DebitCredit movements() { return rows.stream().map(row -> row.posting().amounts()).reduce(DebitCredit.ZERO, DebitCredit::add); }
    public Money closingBalance() { return openingBalance.add(movements().net()); }
}
