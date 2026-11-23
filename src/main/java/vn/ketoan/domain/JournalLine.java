package vn.ketoan.domain;

import java.util.Optional;

import vn.ketoan.domain.value.Money;

/** An immutable numbered ledger row, distinct from an editable draft's input. */
public final class JournalLine {

    private final int lineNumber;
    private final VoucherLine data;

    public JournalLine(int lineNumber, String accountCode, BalanceSide side, Money amount,
                       String partnerCode, String moneyAccountCode) {
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("Journal line number must be positive");
        }
        this.lineNumber = lineNumber;
        this.data = new VoucherLine(accountCode, side, amount, partnerCode, moneyAccountCode);
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getAccountCode() {
        return data.getAccountCode();
    }

    public BalanceSide getSide() {
        return data.getSide();
    }

    public Money getAmount() {
        return data.getAmount();
    }

    public Money getDebit() {
        return data.getDebit();
    }

    public Money getCredit() {
        return data.getCredit();
    }

    public Optional<String> getPartnerCode() {
        return data.getPartnerCode();
    }

    public Optional<String> getMoneyAccountCode() {
        return data.getMoneyAccountCode();
    }
}
