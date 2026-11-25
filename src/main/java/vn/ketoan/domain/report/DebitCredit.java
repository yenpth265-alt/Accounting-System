package vn.ketoan.domain.report;

import java.util.Objects;
import vn.ketoan.domain.value.Money;

/** Nonnegative debit/credit columns. Both may be populated for gross partner balances. */
public record DebitCredit(Money debit, Money credit) {
    public static final DebitCredit ZERO = new DebitCredit(Money.ZERO, Money.ZERO);
    public DebitCredit {
        Objects.requireNonNull(debit, "Debit is required");
        Objects.requireNonNull(credit, "Credit is required");
        if (debit.isNegative() || credit.isNegative()) { throw new IllegalArgumentException("Report debit and credit columns cannot be negative"); }
    }
    public static DebitCredit fromNet(Money net) {
        return net.isNegative() ? new DebitCredit(Money.ZERO, net.negate()) : new DebitCredit(net, Money.ZERO);
    }
    public Money net() { return debit.subtract(credit); }
    public DebitCredit add(DebitCredit other) { return new DebitCredit(debit.add(other.debit), credit.add(other.credit)); }
    public boolean balanced() { return debit.equals(credit); }
}
