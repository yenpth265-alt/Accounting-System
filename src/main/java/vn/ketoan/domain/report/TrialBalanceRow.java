package vn.ketoan.domain.report;

import java.util.Objects;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountType;

public record TrialBalanceRow(String accountCode, String accountName, AccountType type,
                              DebitCredit opening, DebitCredit movements, DebitCredit closing) {
    public TrialBalanceRow {
        accountCode = Account.normalizeCode(accountCode);
        Objects.requireNonNull(accountName, "Account name is required");
        Objects.requireNonNull(type, "Account type is required");
        Objects.requireNonNull(opening, "Opening balance is required");
        Objects.requireNonNull(movements, "Movements are required");
        Objects.requireNonNull(closing, "Closing balance is required");
        if (!opening.net().add(movements.net()).equals(closing.net())) {
            throw new IllegalArgumentException("Trial balance row does not reconcile");
        }
    }
}
