package vn.ketoan.domain.report;

import java.util.List;
import java.util.Objects;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.value.Money;

/** Business revenue/expense movements, including adjustments and reversals, excluding generated transfers. */
public record IncomeStatement(ReportPeriod period, boolean periodsClosed, List<IncomeStatementLine> rows) {
    public IncomeStatement {
        Objects.requireNonNull(period, "Income statement period is required");
        rows = List.copyOf(rows);
    }
    public Money revenue() { return total(AccountType.REVENUE); }
    public Money expense() { return total(AccountType.EXPENSE); }
    public Money profit() { return revenue().subtract(expense()); }
    private Money total(AccountType type) { return rows.stream().filter(row -> row.type() == type).map(IncomeStatementLine::amount).reduce(Money.ZERO, Money::add); }
}
