package vn.ketoan.domain;

import java.time.YearMonth;
import java.util.Objects;

import vn.ketoan.domain.value.Money;

public record PrepaidExpensePeriod(YearMonth month, Money amount) {

    public PrepaidExpensePeriod {
        Objects.requireNonNull(month, "Allocation month is required");
        Objects.requireNonNull(amount, "Allocation amount is required");
        if (month.getYear() < 1000 || month.getYear() > 9999 || !amount.isPositive()
                || amount.toBigDecimal().precision() > 19) {
            throw new IllegalArgumentException("A prepaid period requires a valid month and positive DECIMAL(19,0) amount");
        }
    }
}
