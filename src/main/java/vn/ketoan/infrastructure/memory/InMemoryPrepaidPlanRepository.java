package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.PrepaidPlanRepository;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidPlanCancellation;

public final class InMemoryPrepaidPlanRepository implements PrepaidPlanRepository {

    private final Map<UUID, PrepaidExpensePlan> plans = new LinkedHashMap<>();
    private final Map<UUID, PrepaidPlanCancellation> cancellations = new LinkedHashMap<>();

    @Override public Optional<PrepaidExpensePlan> findById(UUID id) {
        return Optional.ofNullable(plans.get(Objects.requireNonNull(id, "Plan id is required")));
    }
    @Override public List<PrepaidExpensePlan> findAll() { return List.copyOf(plans.values()); }
    @Override public void append(PrepaidExpensePlan plan) {
        Objects.requireNonNull(plan, "Prepaid plan is required");
        if (plans.containsKey(plan.id()) || plans.values().stream().anyMatch(value ->
                value.request().reference().equals(plan.request().reference()))) {
            throw new IllegalStateException("Prepaid plan id or reference already exists");
        }
        plans.put(plan.id(), plan);
    }
    @Override public Optional<PrepaidPlanCancellation> findCancellation(UUID id) {
        return Optional.ofNullable(cancellations.get(Objects.requireNonNull(id, "Plan id is required")));
    }
    @Override public List<PrepaidPlanCancellation> findCancellations() { return List.copyOf(cancellations.values()); }
    @Override public void appendCancellation(PrepaidPlanCancellation cancellation) {
        Objects.requireNonNull(cancellation, "Plan cancellation is required");
        if (!plans.containsKey(cancellation.planId())) { throw new IllegalArgumentException("Prepaid plan not found"); }
        if (cancellations.putIfAbsent(cancellation.planId(), cancellation) != null) {
            throw new IllegalStateException("Prepaid plan is already cancelled");
        }
    }
}
