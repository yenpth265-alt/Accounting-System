package vn.ketoan.application.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.AllocationUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.OpenItemBook;
import vn.ketoan.domain.Permission;

/** Matches already posted amounts; it never writes journal rows or changes account balances. */
public final class DebtAllocationService implements AllocationUseCases {

    private final UnitOfWork transactions;
    private final Clock clock;

    public DebtAllocationService(UnitOfWork transactions, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public DebtAllocation allocate(AllocationRequest request, long actorId) {
        Objects.requireNonNull(request, "Allocation request is required");
        return transactions.execute(session -> {
            requireActor(session, actorId, Permission.ALLOCATE_DEBT);
            Optional<DebtAllocation> existing = session.allocations().findById(request.id());
            if (existing.isPresent()) {
                if (!existing.orElseThrow().request().equals(request)) {
                    throw new IllegalArgumentException("Allocation id was already used for a different request");
                }
                return existing.orElseThrow();
            }
            requireOpenPeriod(session, request.effectiveDate());
            DebtAllocation allocation = new DebtAllocation(request, actorId, clock.instant());
            List<DebtAllocation> candidate = new ArrayList<>(session.allocations().findAll());
            candidate.add(allocation);
            new OpenItemBook(session.accounts().findAll(), session.journal().findAll(),
                    candidate, session.allocations().findCancellations());
            session.allocations().append(allocation);
            return allocation;
        });
    }

    @Override
    public AllocationCancellation cancel(UUID allocationId, LocalDate effectiveDate, String reason, long actorId) {
        Objects.requireNonNull(allocationId, "Allocation id is required");
        return transactions.execute(session -> {
            requireActor(session, actorId, Permission.CANCEL_DEBT_ALLOCATION);
            session.allocations().findById(allocationId).orElseThrow(
                    () -> new IllegalArgumentException("Allocation not found: " + allocationId));
            AllocationCancellation cancellation = new AllocationCancellation(
                    allocationId, effectiveDate, reason, actorId, clock.instant());
            Optional<AllocationCancellation> existing = session.allocations().findCancellation(allocationId);
            if (existing.isPresent()) {
                AllocationCancellation recorded = existing.orElseThrow();
                if (!recorded.effectiveDate().equals(cancellation.effectiveDate())
                        || !recorded.reason().equals(cancellation.reason())) {
                    throw new IllegalArgumentException("Allocation was already cancelled with different details");
                }
                return recorded;
            }
            requireOpenPeriod(session, effectiveDate);
            List<AllocationCancellation> candidate = new ArrayList<>(session.allocations().findCancellations());
            candidate.add(cancellation);
            new OpenItemBook(session.accounts().findAll(), session.journal().findAll(),
                    session.allocations().findAll(), candidate);
            session.allocations().appendCancellation(cancellation);
            return cancellation;
        });
    }

    private void requireActor(AccountingSession session, long actorId, Permission permission) {
        session.users().findById(actorId).orElseThrow(() -> new AccessDeniedException("Unknown user: " + actorId))
                .requirePermission(permission);
    }

    private void requireOpenPeriod(AccountingSession session, LocalDate date) {
        session.periods().findByMonth(YearMonth.from(date)).orElseThrow(
                () -> new IllegalArgumentException("Accounting period not found: " + YearMonth.from(date)))
                .requireOpenForPosting(date);
    }
}
