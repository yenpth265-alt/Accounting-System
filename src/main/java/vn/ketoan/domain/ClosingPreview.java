package vn.ketoan.domain;

import java.util.List;
import java.util.Objects;
import vn.ketoan.domain.value.Money;

/** Signed revenue, expense and profit, calculated from ordinary journal entries only. */
public record ClosingPreview(Money revenue, Money expense, Money profit,
                             String sourceChecksum, List<Draft> vouchers) {
    public ClosingPreview {
        Objects.requireNonNull(revenue, "Revenue is required");
        Objects.requireNonNull(expense, "Expense is required");
        Objects.requireNonNull(profit, "Profit is required");
        if (!revenue.subtract(expense).equals(profit) || sourceChecksum == null
                || !sourceChecksum.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Closing totals or checksum are inconsistent");
        }
        for (Money amount : List.of(revenue, expense, profit)) {
            if (amount.toBigDecimal().precision() > 19) {
                throw new IllegalArgumentException("Closing totals must fit DECIMAL(19,0)");
            }
        }
        vouchers = List.copyOf(vouchers);
    }
}
