package vn.ketoan.application.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.CorrectionUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.domain.AdjustmentRequest;
import vn.ketoan.domain.CorrectionKind;
import vn.ketoan.domain.CorrectionRecord;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherStatus;

public final class CorrectionService implements CorrectionUseCases {

    private final UnitOfWork transactions;
    private final VoucherValidatorFactory validators;
    private final Clock clock;

    public CorrectionService(UnitOfWork transactions, VoucherValidatorFactory validators, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.validators = Objects.requireNonNull(validators, "Validator factory is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public VoucherDocument createAdjustment(AdjustmentRequest request, long actorId) {
        Objects.requireNonNull(request, "Adjustment request is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.CREATE_VOUCHER);
            return create(session, request.id(), request.sourceDocumentId(), CorrectionKind.ADJUSTMENT,
                    request.reason(), request.content(), actorId);
        });
    }

    @Override
    public VoucherDocument createReversal(ReversalRequest request, long actorId) {
        Objects.requireNonNull(request, "Reversal request is required");
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.CREATE_VOUCHER);
            var source = session.journal().findBySourceDocumentId(request.sourceDocumentId()).orElseThrow(
                    () -> new IllegalArgumentException("Reversal source must already be posted"));
            return create(session, request.id(), request.sourceDocumentId(), CorrectionKind.REVERSAL,
                    request.reason(), request.toDraft(source), actorId);
        });
    }

    private VoucherDocument create(AccountingSession session, java.util.UUID id, java.util.UUID sourceId,
                                     CorrectionKind kind, String reason, Draft input, long actorId) {
        var existing = session.corrections().findById(id);
        VoucherSnapshot initial = VoucherSnapshot.from(new GeneralCreator().create(input));
        if (existing.isPresent()) {
            CorrectionRecord record = existing.orElseThrow();
            VoucherDocument target = session.documents().findById(record.targetDocumentId()).orElseThrow();
            if (record.kind() != kind || !record.sourceDocumentId().equals(sourceId) || !record.reason().equals(reason)
                    || record.createdBy() != actorId || !target.getHistory().getFirst().content().equals(initial)) {
                throw new IllegalArgumentException("Correction request id was already used for different input");
            }
            return target;
        }
        VoucherDocument source = session.documents().findById(sourceId).orElseThrow(
                () -> new IllegalArgumentException("Source document not found: " + sourceId));
        if (source.getStatus() != VoucherStatus.POSTED) {
            throw new IllegalArgumentException("Corrections require a posted source document");
        }
        var validator = Objects.requireNonNull(validators.create(session), "Validator is required");
        VoucherWorkflowService workflow = new VoucherWorkflowService(session.users(), session.documents(), validator, clock);
        VoucherDocument target = workflow.create(new GeneralCreator(), input, actorId);
        validator.validate(target.getContent());
        session.corrections().append(new CorrectionRecord(id, sourceId, target.getId(), kind, reason, actorId, target.getCreatedAt()));
        AccountingBooks.prepaid(session);
        return target;
    }

    @Override
    public List<CorrectionRecord> history(long actorId) {
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            return List.copyOf(session.corrections().findAll());
        });
    }
}
