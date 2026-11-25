package vn.ketoan.domain;

import java.util.Objects;

import vn.ketoan.domain.value.Money;

/** One recognition component, such as revenue, expense or a separately entered tax amount. */
public final class RecognitionItem {

    private final String accountCode;
    private final Money amount;

    public RecognitionItem(String accountCode, Money amount) {
        this.accountCode = Account.normalizeCode(accountCode);
        this.amount = Objects.requireNonNull(amount, "Recognition amount is required");
        if (!amount.isPositive() || amount.toBigDecimal().precision() > 19) {
            throw new IllegalArgumentException("Recognition amounts must be positive and fit DECIMAL(19,0)");
        }
    }

    public String getAccountCode() {
        return accountCode;
    }

    public Money getAmount() {
        return amount;
    }
}
