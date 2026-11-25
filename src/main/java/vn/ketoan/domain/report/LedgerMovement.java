package vn.ketoan.domain.report;

import java.util.Objects;
import vn.ketoan.domain.value.Money;

public record LedgerMovement(JournalRow posting, Money runningBalance) {
    public LedgerMovement {
        Objects.requireNonNull(posting, "Ledger posting is required");
        Objects.requireNonNull(runningBalance, "Running balance is required");
    }
}
