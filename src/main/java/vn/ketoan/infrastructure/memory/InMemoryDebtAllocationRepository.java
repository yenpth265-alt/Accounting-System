package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.DebtAllocationRepository;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.DebtAllocation;

public final class InMemoryDebtAllocationRepository implements DebtAllocationRepository {

    private final Map<UUID, DebtAllocation> allocations = new LinkedHashMap<>();
    private final Map<UUID, AllocationCancellation> cancellations = new LinkedHashMap<>();

    @Override
    public synchronized Optional<DebtAllocation> findById(UUID id) {
        return Optional.ofNullable(allocations.get(Objects.requireNonNull(id, "Allocation id is required")));
    }

    @Override
    public synchronized List<DebtAllocation> findAll() {
        return List.copyOf(allocations.values());
    }

    @Override
    public synchronized Optional<AllocationCancellation> findCancellation(UUID allocationId) {
        return Optional.ofNullable(cancellations.get(Objects.requireNonNull(allocationId, "Allocation id is required")));
    }

    @Override
    public synchronized List<AllocationCancellation> findCancellations() {
        return List.copyOf(cancellations.values());
    }

    @Override
    public synchronized void append(DebtAllocation allocation) {
        Objects.requireNonNull(allocation, "Allocation is required");
        if (allocations.putIfAbsent(allocation.id(), allocation) != null) {
            throw new IllegalStateException("Allocation id already exists");
        }
    }

    @Override
    public synchronized void appendCancellation(AllocationCancellation cancellation) {
        Objects.requireNonNull(cancellation, "Cancellation is required");
        if (!allocations.containsKey(cancellation.allocationId())) {
            throw new IllegalArgumentException("Cancellation requires an existing allocation");
        }
        if (cancellations.putIfAbsent(cancellation.allocationId(), cancellation) != null) {
            throw new IllegalStateException("Allocation is already cancelled");
        }
    }
}
