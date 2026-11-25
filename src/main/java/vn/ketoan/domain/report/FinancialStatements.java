package vn.ketoan.domain.report;

import java.util.Objects;

/** These three reports are produced from the same UnitOfWork read. */
public record FinancialStatements(TrialBalance trialBalance, IncomeStatement incomeStatement, BalanceSheet balanceSheet) {
    public FinancialStatements {
        Objects.requireNonNull(trialBalance, "Trial balance is required");
        Objects.requireNonNull(incomeStatement, "Income statement is required");
        Objects.requireNonNull(balanceSheet, "Balance sheet is required");
        if (!trialBalance.period().equals(incomeStatement.period()) || !trialBalance.period().to().equals(balanceSheet.asOf())
                || trialBalance.periodsClosed() != incomeStatement.periodsClosed()) {
            throw new IllegalArgumentException("Financial statements must use the same reporting period");
        }
    }
}
