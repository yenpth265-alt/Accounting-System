package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.domain.value.Money;

/** Reuse id when retrying the same allocation; a different allocation needs a new id. */
public record AllocationRequest(UUID id, JournalLineKey charge, JournalLineKey settlement,
                                Money amount, LocalDate effectiveDate, String note) {

    public AllocationRequest {
        Objects.requireNonNull(id, "Allocation id is required");
        Objects.requireNonNull(charge, "Debt line is required");
        Objects.requireNonNull(settlement, "Settlement line is required");
        Objects.requireNonNull(amount, "Allocation amount is required");
        effectiveDate = DomainValidation.date(effectiveDate, "Allocation date");
        note = DomainValidation.requiredText(note, 255, "Allocation note");
        if (charge.equals(settlement) || !amount.isPositive() || amount.toBigDecimal().precision() > 19) {
            throw new IllegalArgumentException("Allocation requires different lines and a positive DECIMAL(19,0) amount");
        }
    }
}
