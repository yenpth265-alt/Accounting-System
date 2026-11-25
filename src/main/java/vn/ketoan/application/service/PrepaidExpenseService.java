package vn.ketoan.application.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.application.port.PrepaidExpenseUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.ExpenseAllocationStrategy;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.PrepaidAllocationRecord;
import vn.ketoan.domain.PrepaidAllocationRequest;
import vn.ketoan.domain.PrepaidExpensePeriod;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidExpenseStatement;
import vn.ketoan.domain.PrepaidPlanCancellation;
import vn.ketoan.domain.PrepaidPlanRequest;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherSnapshot;

public final class PrepaidExpenseService implements PrepaidExpenseUseCases {

    private final UnitOfWork transactions;
    private final VoucherValidatorFactory validators;
    private final Clock clock;
    private final ExpenseAllocationStrategy strategy;

    public PrepaidExpenseService(UnitOfWork transactions, VoucherValidatorFactory validators, Clock clock,
                                   ExpenseAllocationStrategy strategy) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.validators = Objects.requireNonNull(validators, "Validator factory is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.strategy = Objects.requireNonNull(strategy, "Expense allocation strategy is required");
    }

    @Override
    public PrepaidExpensePlan createPlan(PrepaidPlanRequest request, long actorId) {
        Objects.requireNonNull(request, "Prepaid plan request is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.CREATE_VOUCHER);
            var existing = session.prepaidPlans().findById(request.id());
            if (existing.isPresent()) {
                PrepaidExpensePlan plan = existing.orElseThrow();
                if (!plan.request().equals(request) || plan.createdBy() != actorId) {
                    throw new IllegalArgumentException("Prepaid plan id was already used for different input");
                }
                return plan;
            }
            var source = session.journal().findById(request.source().journalEntryId()).orElseThrow(
                    () -> new IllegalArgumentException("Prepaid source must be posted"));
            var line = source.getLines().stream().filter(value -> value.getLineNumber() == request.source().lineNumber())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Prepaid source line not found"));
            new AccountService(session.accounts()).requirePostingAccount(line.getAccountCode());
            new AccountService(session.accounts()).requirePostingAccount(request.expenseAccountCode());
            var amounts = List.copyOf(strategy.allocate(line.getAmount(), request.months()));
            List<PrepaidExpensePeriod> schedule = new ArrayList<>();
            for (int index = 0; index < amounts.size(); index++) {
                schedule.add(new PrepaidExpensePeriod(request.firstMonth().plusMonths(index), amounts.get(index)));
            }
            PrepaidExpensePlan plan = new PrepaidExpensePlan(request, schedule, actorId, clock.instant());
            session.prepaidPlans().append(plan);
            AccountingBooks.prepaid(session);
            return plan;
        });
    }

    @Override
    public VoucherDocument createInstallment(PrepaidAllocationRequest request, long actorId) {
        Objects.requireNonNull(request, "Prepaid allocation request is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.CREATE_VOUCHER);
            var plan = session.prepaidPlans().findById(request.planId()).orElseThrow(
                    () -> new IllegalArgumentException("Prepaid plan not found: " + request.planId()));
            var book = AccountingBooks.prepaid(session);
            var draft = book.draft(plan, request);
            VoucherSnapshot initial = VoucherSnapshot.from(new GeneralCreator().create(draft));
            var existing = session.prepaidAllocations().findById(request.id());
            if (existing.isPresent()) {
                var record = existing.orElseThrow();
                VoucherDocument target = session.documents().findById(record.targetDocumentId()).orElseThrow();
                if (!record.planId().equals(request.planId()) || record.createdBy() != actorId
                        || !target.getHistory().getFirst().content().equals(initial)) {
                    throw new IllegalArgumentException("Prepaid allocation id was already used for different input");
                }
                return target;
            }
            if (session.prepaidPlans().findCancellation(plan.id()).isPresent()) {
                throw new IllegalStateException("A cancelled prepaid plan cannot create more installments");
            }
            var validator = Objects.requireNonNull(validators.create(session), "Validator is required");
            VoucherWorkflowService workflow = new VoucherWorkflowService(session.users(), session.documents(), validator, clock);
            VoucherDocument target = workflow.create(new GeneralCreator(), draft, actorId);
            validator.validate(target.getContent());
            session.prepaidAllocations().append(new PrepaidAllocationRecord(request.id(), plan.id(),
                    YearMonth.from(request.postingDate()), target.getId(), actorId, target.getCreatedAt()));
            AccountingBooks.prepaid(session);
            return target;
        });
    }

    @Override
    public PrepaidPlanCancellation cancelPlan(UUID id, LocalDate date, String reason, long actorId) {
        Objects.requireNonNull(id, "Prepaid plan id is required");
        return transactions.execute(session -> {
            var actor = AccountingBooks.actor(session, actorId, Permission.CANCEL_VOUCHER);
            var plan = session.prepaidPlans().findById(id).orElseThrow(() -> new IllegalArgumentException("Prepaid plan not found: " + id));
            if (actorId != plan.createdBy() && !actor.hasRole(UserRole.CHIEF)) {
                throw new AccessDeniedException("Only the plan creator or a chief accountant may cancel this plan");
            }
            PrepaidPlanCancellation cancellation = new PrepaidPlanCancellation(id, date, reason, actorId, clock.instant());
            var existing = session.prepaidPlans().findCancellation(id);
            if (existing.isPresent()) {
                var recorded = existing.orElseThrow();
                if (!recorded.effectiveDate().equals(cancellation.effectiveDate()) || !recorded.reason().equals(cancellation.reason())) {
                    throw new IllegalArgumentException("Plan was already cancelled with different details");
                }
                return recorded;
            }
            session.periods().findByMonth(YearMonth.from(date)).orElseThrow(
                    () -> new IllegalArgumentException("Cancellation period not found")).requireOpenForPosting(date);
            session.prepaidPlans().appendCancellation(cancellation);
            AccountingBooks.prepaid(session);
            return cancellation;
        });
    }

    @Override
    public PrepaidExpenseStatement statement(UUID id, LocalDate asOf, long actorId) {
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            return AccountingBooks.prepaid(session).statement(id, asOf);
        });
    }

    @Override
    public List<PrepaidExpensePlan> plans(long actorId) {
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            return List.copyOf(session.prepaidPlans().findAll());
        });
    }

    @Override
    public List<PrepaidAllocationRecord> installments(UUID planId, long actorId) {
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            session.prepaidPlans().findById(planId).orElseThrow(() -> new IllegalArgumentException("Prepaid plan not found"));
            return session.prepaidAllocations().findAll().stream().filter(value -> value.planId().equals(planId)).toList();
        });
    }
}
