package vn.ketoan.domain.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An immutable amount in whole Vietnamese dong. Negative amounts are permitted
 * for balances and adjustments; a voucher line must validate its own sign.
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        Objects.requireNonNull(amount, "Amount is required");
        try {
            this.amount = amount.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "VND amounts must be whole dong", exception);
        }
    }

    public static Money of(String amount) {
        Objects.requireNonNull(amount, "Amount is required");
        return of(new BigDecimal(amount));
    }

    public static Money of(long amount) {
        return of(BigDecimal.valueOf(amount));
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "Amount to add is required");
        return of(amount.add(other.amount));
    }

    public Money subtract(Money other) {
        Objects.requireNonNull(other, "Amount to subtract is required");
        return of(amount.subtract(other.amount));
    }

    public Money negate() {
        return of(amount.negate());
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public BigDecimal toBigDecimal() {
        return amount;
    }

    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "Amount to compare is required");
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Money money && amount.equals(money.amount);
    }

    @Override
    public int hashCode() {
        return amount.hashCode();
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
