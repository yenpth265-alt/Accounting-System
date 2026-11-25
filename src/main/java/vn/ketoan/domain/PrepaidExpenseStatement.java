package vn.ketoan.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import vn.ketoan.domain.value.Money;

public record PrepaidExpenseStatement(UUID planId, String reference, LocalDate asOf, Money original,
                                      Money recognized, Money remainingAsset, boolean cancelled, List<Row> schedule) {

    public PrepaidExpenseStatement {
        schedule = List.copyOf(schedule);
    }

    public record Row(YearMonth month, Money planned, Money recognized) { }
}
