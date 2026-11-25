package vn.ketoan.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.domain.value.Money;

/** Frozen schedule: later changes to a strategy cannot alter the amounts already agreed. */
public record PrepaidExpensePlan(PrepaidPlanRequest request, List<PrepaidExpensePeriod> schedule,
                                 long createdBy, Instant createdAt) {

    public PrepaidExpensePlan {
        Objects.requireNonNull(request, "Prepaid plan request is required");
        schedule = List.copyOf(schedule);
        Objects.requireNonNull(createdAt, "Prepaid plan creation time is required");
        if (createdBy <= 0 || schedule.size() != request.months()) {
            throw new IllegalArgumentException("Plan actor and schedule length are inconsistent");
        }
        for (int index = 0; index < schedule.size(); index++) {
            if (!schedule.get(index).month().equals(request.firstMonth().plusMonths(index))) {
                throw new IllegalArgumentException("Prepaid months must be consecutive from the first month");
            }
        }
        if (schedule.stream().map(PrepaidExpensePeriod::amount).reduce(Money.ZERO, Money::add).toBigDecimal().precision() > 19) {
            throw new IllegalArgumentException("Prepaid plan total must fit DECIMAL(19,0)");
        }
    }

    public UUID id() { return request.id(); }
    public Money total() { return schedule.stream().map(PrepaidExpensePeriod::amount).reduce(Money.ZERO, Money::add); }

    public PrepaidExpensePeriod period(java.time.YearMonth month) {
        return schedule.stream().filter(value -> value.month().equals(month)).findFirst().orElseThrow(
                () -> new IllegalArgumentException("Month does not belong to the prepaid schedule: " + month));
    }
}
