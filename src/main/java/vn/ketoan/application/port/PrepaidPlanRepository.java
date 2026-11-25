package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidPlanCancellation;

public interface PrepaidPlanRepository {

    Optional<PrepaidExpensePlan> findById(UUID id);
    List<PrepaidExpensePlan> findAll();
    void append(PrepaidExpensePlan plan);
    Optional<PrepaidPlanCancellation> findCancellation(UUID planId);
    List<PrepaidPlanCancellation> findCancellations();
    void appendCancellation(PrepaidPlanCancellation cancellation);
}
