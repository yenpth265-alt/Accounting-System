package vn.ketoan.application.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.application.port.RecognitionUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ExpenseCreator;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherDocument;

/** Turns business input into validated drafts, with atomic document/history writes. */
public final class RecognitionService implements RecognitionUseCases {

    private final UnitOfWork transactions;
    private final VoucherValidatorFactory validators;
    private final Clock clock;

    public RecognitionService(UnitOfWork transactions, VoucherValidatorFactory validators, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.validators = Objects.requireNonNull(validators, "Validator factory is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public VoucherDocument createRevenue(RecognitionDraft input, long actorId) {
        return create(new RevenueCreator(), requireInput(input).toRevenueDraft(), actorId);
    }

    @Override
    public VoucherDocument createExpense(RecognitionDraft input, long actorId) {
        return create(new ExpenseCreator(), requireInput(input).toExpenseDraft(), actorId);
    }

    @Override
    public VoucherDocument editRevenue(UUID documentId, long expectedVersion, RecognitionDraft input, long actorId) {
        return edit(documentId, expectedVersion, new RevenueCreator(), requireInput(input).toRevenueDraft(), actorId);
    }

    @Override
    public VoucherDocument editExpense(UUID documentId, long expectedVersion, RecognitionDraft input, long actorId) {
        return edit(documentId, expectedVersion, new ExpenseCreator(), requireInput(input).toExpenseDraft(), actorId);
    }

    private VoucherDocument create(VoucherCreator creator, Draft draft, long actorId) {
        return transactions.execute(session -> {
            VoucherValidator validator = Objects.requireNonNull(validators.create(session), "Validator is required");
            VoucherWorkflowService workflow = new VoucherWorkflowService(
                    session.users(), session.documents(), validator, clock);
            VoucherDocument document = workflow.create(creator, draft, actorId);
            // Validation failure discards the inserted document and its creation history together.
            validator.validate(document.getContent());
            return document;
        });
    }

    private VoucherDocument edit(UUID id, long version, VoucherCreator creator, Draft draft, long actorId) {
        return transactions.execute(session -> {
            VoucherValidator validator = Objects.requireNonNull(validators.create(session), "Validator is required");
            VoucherWorkflowService workflow = new VoucherWorkflowService(
                    session.users(), session.documents(), validator, clock);
            VoucherDocument document = workflow.edit(id, version, creator, draft, actorId);
            // A failed edit preserves the previous content, version and complete audit history.
            validator.validate(document.getContent());
            return document;
        });
    }

    private RecognitionDraft requireInput(RecognitionDraft input) {
        return Objects.requireNonNull(input, "Recognition input is required");
    }
}
