package vn.ketoan.domain.report;

import java.util.Objects;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.value.Money;

public record IncomeStatementLine(String accountCode, String accountName, AccountType type, DebitCredit movements) {
    public IncomeStatementLine {
        accountCode = Account.normalizeCode(accountCode);
        Objects.requireNonNull(accountName, "Account name is required");
        Objects.requireNonNull(movements, "Movements are required");
        if (type != AccountType.REVENUE && type != AccountType.EXPENSE) {
            throw new IllegalArgumentException("Income statement lines require revenue or expense accounts");
        }
    }
    public Money amount() { return type == AccountType.REVENUE ? movements.net().negate() : movements.net(); }
}
