package vn.ketoan.domain;

import java.util.Objects;
import java.util.Optional;

import vn.ketoan.domain.value.Money;

/** One immutable draft line. The amount is positive and belongs to exactly one side. */
public final class VoucherLine {

    private final String accountCode;
    private final BalanceSide side;
    private final Money amount;
    private final String partnerCode;
    private final String moneyAccountCode;

    public VoucherLine(String accountCode, BalanceSide side, Money amount,
                       String partnerCode, String moneyAccountCode) {
        this.accountCode = Account.normalizeCode(accountCode);
        this.side = Objects.requireNonNull(side, "Posting side is required");
        this.amount = Objects.requireNonNull(amount, "Line amount is required");
        this.partnerCode = partnerCode == null ? null : Partner.normalizeCode(partnerCode);
        this.moneyAccountCode = moneyAccountCode == null ? null : MoneyAccount.normalizeCode(moneyAccountCode);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A voucher line amount must be positive");
        }
        if (amount.toBigDecimal().precision() > 19) {
            throw new IllegalArgumentException("A voucher line amount must fit DECIMAL(19,0)");
        }
    }

    public static VoucherLine debit(String accountCode, Money amount,
                                    String partnerCode, String moneyAccountCode) {
        return new VoucherLine(accountCode, BalanceSide.DEBIT, amount, partnerCode, moneyAccountCode);
    }

    public static VoucherLine credit(String accountCode, Money amount,
                                     String partnerCode, String moneyAccountCode) {
        return new VoucherLine(accountCode, BalanceSide.CREDIT, amount, partnerCode, moneyAccountCode);
    }

    public String getAccountCode() {
        return accountCode;
    }

    public BalanceSide getSide() {
        return side;
    }

    public Money getAmount() {
        return amount;
    }

    public Money getDebit() {
        return side == BalanceSide.DEBIT ? amount : Money.ZERO;
    }

    public Money getCredit() {
        return side == BalanceSide.CREDIT ? amount : Money.ZERO;
    }

    public Optional<String> getPartnerCode() {
        return Optional.ofNullable(partnerCode);
    }

    public Optional<String> getMoneyAccountCode() {
        return Optional.ofNullable(moneyAccountCode);
    }
}
