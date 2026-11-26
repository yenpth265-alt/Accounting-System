package vn.ketoan.application.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import vn.ketoan.application.port.CashFlowClassificationUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.report.CashFlowBook;
import vn.ketoan.domain.report.CashFlowClassification;
import vn.ketoan.domain.report.CashFlowClassificationRequest;

public final class CashFlowClassificationService implements CashFlowClassificationUseCases {
    private final UnitOfWork transactions;
    private final Clock clock;
    public CashFlowClassificationService(UnitOfWork transactions, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public CashFlowClassification classify(CashFlowClassificationRequest request, long actorId) {
        Objects.requireNonNull(request, "Classification request is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.CLASSIFY_CASH_FLOW);
            var existing = session.cashFlowClassifications().findById(request.id());
            if (existing.isPresent()) {
                var value = existing.orElseThrow();
                if (!value.entryId().equals(request.entryId()) || value.revision() != Math.incrementExact(request.expectedRevision())
                        || !value.reason().equals(request.reason()) || !value.internalTransfer().equals(request.internalTransfer())
                        || !value.splits().equals(request.splits()) || value.recordedBy() != actorId) {
                    throw new IllegalArgumentException("Classification request id was already used for different input");
                }
                return value;
            }
            session.journal().findById(request.entryId()).orElseThrow(() -> new IllegalArgumentException("Classification requires a posted journal entry"));
            var records = session.cashFlowClassifications().findAll();
            var previous = new CashFlowBook(session.accounts().findAll(), session.journal().findAll(), session.corrections().findAll(), records).latest(request.entryId());
            if ((previous == null ? 0 : previous.revision()) != request.expectedRevision()) {
                throw new IllegalStateException("Cash flow classification revision changed; reload it before editing");
            }
            var value = new CashFlowClassification(request.id(), request.entryId(), Math.incrementExact(request.expectedRevision()), request.reason(),
                    request.internalTransfer(), request.splits(), actorId, clock.instant());
            session.cashFlowClassifications().append(value);
            new CashFlowBook(session.accounts().findAll(), session.journal().findAll(), session.corrections().findAll(), session.cashFlowClassifications().findAll());
            return value;
        });
    }
    @Override public List<CashFlowClassification> history(long actorId) {
        return transactions.execute(session -> { AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS); return session.cashFlowClassifications().findAll(); });
    }
}
