package vn.ketoan.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import vn.ketoan.domain.PrepaidAllocationRecord;
import vn.ketoan.domain.PrepaidAllocationRequest;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidExpenseStatement;
import vn.ketoan.domain.PrepaidPlanCancellation;
import vn.ketoan.domain.PrepaidPlanRequest;
import vn.ketoan.domain.VoucherDocument;

public interface PrepaidExpenseUseCases {

    PrepaidExpensePlan createPlan(PrepaidPlanRequest request, long actorId);
    VoucherDocument createInstallment(PrepaidAllocationRequest request, long actorId);
    PrepaidPlanCancellation cancelPlan(UUID id, LocalDate date, String reason, long actorId);
    PrepaidExpenseStatement statement(UUID id, LocalDate asOf, long actorId);
    List<PrepaidExpensePlan> plans(long actorId);
    List<PrepaidAllocationRecord> installments(UUID planId, long actorId);
}
