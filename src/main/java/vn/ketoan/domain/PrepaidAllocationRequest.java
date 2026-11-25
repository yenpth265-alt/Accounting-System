package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record PrepaidAllocationRequest(UUID id, UUID planId, String reference, LocalDate postingDate) {

    public PrepaidAllocationRequest {
        Objects.requireNonNull(id, "Prepaid allocation id is required");
        Objects.requireNonNull(planId, "Prepaid plan id is required");
        reference = DomainValidation.code(reference, 32, "Prepaid allocation reference");
        postingDate = DomainValidation.date(postingDate, "Prepaid allocation date");
    }
}
