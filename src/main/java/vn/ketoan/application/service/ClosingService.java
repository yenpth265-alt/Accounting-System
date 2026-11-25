package vn.ketoan.application.service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.ClosingUseCases;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.ClosingAction;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingBook;
import vn.ketoan.domain.ClosingCalculator;
import vn.ketoan.domain.ClosingEvent;
import vn.ketoan.domain.ClosingPreview;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.ClosingStatus;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.PeriodStatus;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;

public final class ClosingService implements ClosingUseCases {
    private final UnitOfWork transactions;
    private final Clock clock;
    private final List<PostingPolicy> policies;
    public ClosingService(UnitOfWork transactions, Clock clock) { this(transactions, clock, PostingPolicies.standard()); }
    public ClosingService(UnitOfWork transactions, Clock clock, List<PostingPolicy> policies) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.policies = List.copyOf(policies);
    }

    @Override public ClosingPreview preview(ClosingRequest request, long actorId) {
        Objects.requireNonNull(request, "Closing request is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            return new ClosingCalculator().calculate(request, session.accounts().findAll(), sources(session, request.month()));
        });
    }

    @Override public ClosingBatch prepare(ClosingRequest request, long actorId) {
        Objects.requireNonNull(request, "Closing request is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.PREPARE_CLOSING);
            var existing = session.closings().findById(request.id());
            if (existing.isPresent()) {
                var batch = existing.orElseThrow();
                if (!batch.request().equals(request) || batch.preparedBy() != actorId) {
                    throw new IllegalArgumentException("Closing request id was already used for different input");
                }
                return batch;
            }
            var period = session.periods().findByMonth(request.month()).orElseThrow(() -> new IllegalArgumentException("Closing period not found"));
            period.requireOpenForPosting(request.month().atEndOfMonth());
            requireReady(session, request.month());
            ClosingCalculator calculator = new ClosingCalculator();
            calculator.requireZeroTemporaryBalances(session.accounts().findAll(), session.journal().findAll(), request.month().atDay(1).minusDays(1));
            List<JournalEntry> sources = sources(session, request.month());
            ClosingPreview preview = calculator.calculate(request, session.accounts().findAll(), sources);
            new AccountService(session.accounts()).requirePostingAccount(request.resultAccountCode());
            new AccountService(session.accounts()).requirePostingAccount(request.retainedEarningsAccountCode());
            var validator = new VoucherValidationService(session.accounts(), session.partners(), session.moneyAccounts(), session.periods());
            Clock operationClock = operationClock();
            var workflow = new VoucherWorkflowService(session.users(), session.documents(), validator, operationClock);
            List<UUID> ids = new ArrayList<>();
            for (var draft : preview.vouchers()) {
                VoucherDocument created = workflow.create(new GeneralCreator(), draft, actorId);
                ids.add(workflow.submit(created.getId(), created.getVersion(), actorId).getId());
            }
            ClosingBatch batch = new ClosingBatch(request, sources.stream().map(JournalEntry::getId).toList(), ids,
                    preview.sourceChecksum(), preview.revenue(), preview.expense(), preview.profit(), actorId, operationClock.instant());
            session.closings().append(batch);
            session.periods().update(period.beginClosing());
            return batch;
        });
    }

    @Override public ClosingBatch approve(UUID id, long actorId) {
        return transactions.execute(session -> {
            ClosingBatch batch = requireChief(session, id, actorId);
            ClosingStatus status = book(session).status(id);
            if (status == ClosingStatus.APPROVED || status == ClosingStatus.CLOSED) { return batch; }
            if (status != ClosingStatus.PREPARED) { throw new IllegalStateException("Only a prepared closing can be reviewed"); }
            AccountingBooks.actor(session, actorId, Permission.REVIEW_VOUCHER);
            requireReady(session, batch.request().month());
            Clock operationClock = operationClock();
            var workflow = new VoucherWorkflowService(session.users(), session.documents(), new ClosingVoucherValidator(session, batch), operationClock);
            for (UUID documentId : batch.documentIds()) {
                VoucherDocument document = session.documents().findById(documentId).orElseThrow();
                workflow.approve(documentId, document.getVersion(), actorId);
            }
            session.closings().appendEvent(new ClosingEvent(id, ClosingAction.APPROVED, "Approved closing", actorId, operationClock.instant()));
            return batch;
        });
    }

    @Override public ClosingBatch close(UUID id, long actorId) {
        return transactions.execute(session -> {
            ClosingBatch batch = requireChief(session, id, actorId);
            ClosingStatus status = book(session).status(id);
            if (status == ClosingStatus.CLOSED) { return batch; }
            if (status != ClosingStatus.APPROVED) { throw new IllegalStateException("Approve the closing before posting and locking the period"); }
            AccountingBooks.actor(session, actorId, Permission.POST_VOUCHER);
            requireReady(session, batch.request().month());
            Clock operationClock = operationClock();
            var posting = new PostingService(transactions, current -> new ClosingVoucherValidator(current, batch), operationClock, policies);
            for (UUID documentId : batch.documentIds()) {
                VoucherDocument document = session.documents().findById(documentId).orElseThrow();
                posting.postInSession(session, documentId, document.getVersion(), actorId);
            }
            new ClosingCalculator().requireZeroTemporaryBalances(session.accounts().findAll(), session.journal().findAll(), batch.request().month().atEndOfMonth());
            session.periods().update(session.periods().findByMonth(batch.request().month()).orElseThrow().completeClosing());
            session.closings().appendEvent(new ClosingEvent(id, ClosingAction.CLOSED, "Closed accounting period", actorId, operationClock.instant()));
            return batch;
        });
    }

    @Override public ClosingBatch cancel(UUID id, String reason, long actorId) {
        return transactions.execute(session -> {
            ClosingBatch batch = requireChief(session, id, actorId);
            Clock operationClock = operationClock();
            ClosingEvent event = new ClosingEvent(id, ClosingAction.CANCELLED, reason, actorId, operationClock.instant());
            ClosingStatus status = book(session).status(id);
            if (status == ClosingStatus.CANCELLED) {
                var previous = session.closings().findEvents().stream().filter(value -> value.batchId().equals(id) && value.action() == ClosingAction.CANCELLED).findFirst().orElseThrow();
                if (!previous.reason().equals(event.reason())) { throw new IllegalArgumentException("Closing was already cancelled for a different reason"); }
                return batch;
            }
            if (status == ClosingStatus.CLOSED) { throw new IllegalStateException("A completed closing cannot be cancelled; adjust in an open period"); }
            var workflow = new VoucherWorkflowService(session.users(), session.documents(), new ClosingVoucherValidator(session, batch), operationClock);
            for (UUID documentId : batch.documentIds()) {
                VoucherDocument document = session.documents().findById(documentId).orElseThrow();
                if (document.getStatus() == VoucherStatus.PENDING_APPROVAL) {
                    document = workflow.returnForRevision(documentId, document.getVersion(), actorId, reason);
                } else if (document.getStatus() == VoucherStatus.APPROVED) {
                    document = workflow.withdrawApproval(documentId, document.getVersion(), actorId, reason);
                }
                workflow.cancel(documentId, document.getVersion(), actorId, reason);
            }
            session.periods().update(session.periods().findByMonth(batch.request().month()).orElseThrow().cancelClosing());
            session.closings().appendEvent(event);
            return batch;
        });
    }

    @Override public List<ClosingBatch> batches(long actorId) {
        return transactions.execute(session -> { AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS); return session.closings().findAll(); });
    }
    @Override public List<ClosingEvent> history(UUID id, long actorId) {
        Objects.requireNonNull(id, "Closing batch id is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            session.closings().findById(id).orElseThrow(() -> new IllegalArgumentException("Closing batch not found"));
            return session.closings().findEvents().stream().filter(event -> event.batchId().equals(id)).toList();
        });
    }

    private ClosingBatch requireChief(AccountingSession session, UUID id, long actorId) {
        Objects.requireNonNull(id, "Closing batch id is required");
        AccountingBooks.actor(session, actorId, Permission.CLOSE_PERIOD);
        var batch = session.closings().findById(id).orElseThrow(() -> new IllegalArgumentException("Closing batch not found"));
        if (batch.preparedBy() == actorId) { throw new AccessDeniedException("The preparer cannot review, close or cancel their own closing"); }
        return batch;
    }
    private Clock operationClock() { return Clock.fixed(clock.instant(), ZoneOffset.UTC); }
    private List<JournalEntry> sources(AccountingSession session, YearMonth month) {
        var generated = session.closings().findAll().stream().flatMap(batch -> batch.documentIds().stream()).collect(Collectors.toSet());
        return session.journal().findAll().stream().filter(entry -> month.equals(YearMonth.from(entry.getPostingDate()))
                        && !generated.contains(entry.getSourceDocumentId()))
                .sorted(java.util.Comparator.comparing(entry -> entry.getId().toString())).toList();
    }
    private void requireReady(AccountingSession session, YearMonth month) {
        if (session.periods().findAll().stream().anyMatch(period -> period.getMonth().isBefore(month) && period.getStatus() != PeriodStatus.CLOSED)) {
            throw new IllegalStateException("Close earlier accounting periods first");
        }
        var generated = session.closings().findAll().stream().flatMap(batch -> batch.documentIds().stream()).collect(Collectors.toSet());
        if (session.documents().findAll().stream().anyMatch(document -> month.equals(YearMonth.from(document.getContent().getPostingDate()))
                && !generated.contains(document.getId()) && document.getStatus() != VoucherStatus.POSTED && document.getStatus() != VoucherStatus.CANCELLED)) {
            throw new IllegalStateException("Post or cancel all ordinary vouchers in the month before closing");
        }
        var prepaid = AccountingBooks.prepaid(session);
        for (var plan : session.prepaidPlans().findAll()) {
            if (plan.schedule().stream().noneMatch(period -> period.month().equals(month))) { continue; }
            var statement = prepaid.statement(plan.id(), month.atEndOfMonth());
            if (!statement.cancelled() && statement.schedule().stream().anyMatch(row -> row.month().equals(month) && !row.planned().equals(row.recognized()))) {
                throw new IllegalStateException("Post the due prepaid installment before closing: " + plan.request().reference());
            }
        }
    }
    static ClosingBook book(AccountingSession session) {
        return new ClosingBook(session.accounts().findAll(), session.periods().findAll(), session.documents().findAll(), session.journal().findAll(),
                session.closings().findAll(), session.closings().findEvents(), session.corrections().findAll(), session.prepaidPlans().findAll(), session.prepaidAllocations().findAll());
    }
}
