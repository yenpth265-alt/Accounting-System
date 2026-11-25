package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.DebtAllocation;

/** Append-only allocation history; a cancellation is a separate immutable record. */
public interface DebtAllocationRepository {

    Optional<DebtAllocation> findById(UUID id);

    List<DebtAllocation> findAll();

    Optional<AllocationCancellation> findCancellation(UUID allocationId);

    List<AllocationCancellation> findCancellations();

    void append(DebtAllocation allocation);

    void appendCancellation(AllocationCancellation cancellation);
}
