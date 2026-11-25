package vn.ketoan.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import vn.ketoan.domain.value.Money;

/** Whole VND each month, with the rounding remainder assigned to the final month. */
public final class StraightLineAllocation implements ExpenseAllocationStrategy {

    @Override
    public List<Money> allocate(Money total, int months) {
        Objects.requireNonNull(total, "Prepaid amount is required");
        if (months < 1 || months > 120 || total.compareTo(Money.of(months)) < 0) {
            throw new IllegalArgumentException("Use 1 to 120 months and at least one VND per month");
        }
        BigDecimal[] division = total.toBigDecimal().divideAndRemainder(BigDecimal.valueOf(months));
        List<Money> result = new ArrayList<>();
        for (int index = 0; index < months; index++) {
            result.add(Money.of(index == months - 1 ? division[0].add(division[1]) : division[0]));
        }
        return List.copyOf(result);
    }
}
