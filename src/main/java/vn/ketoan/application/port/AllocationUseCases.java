package vn.ketoan.application.port;

import java.time.LocalDate;
import java.util.UUID;

import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.DebtAllocation;

public interface AllocationUseCases {

    DebtAllocation allocate(AllocationRequest request, long actorId);

    AllocationCancellation cancel(UUID allocationId, LocalDate effectiveDate, String reason, long actorId);
}
