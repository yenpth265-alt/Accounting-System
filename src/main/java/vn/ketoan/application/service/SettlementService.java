package vn.ketoan.application.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.SettlementUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.OpenItemBook;
import vn.ketoan.domain.PaymentCreator;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.SettlementDraft;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherDocument;

/** Creates complete settlement drafts. An outstanding invoice is not required for an advance. */
public final class SettlementService implements SettlementUseCases {

    private final UnitOfWork transactions;
    private final VoucherValidatorFactory validators;
    private final Clock clock;

    public SettlementService(UnitOfWork transactions, VoucherValidatorFactory validators, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.validators = Objects.requireNonNull(validators, "Validator factory is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public VoucherDocument createCustomerReceipt(SettlementDraft input, long actorId) {
        return create(input, new ReceiptCreator(), AccountType.ASSET, actorId);
    }

    @Override
    public VoucherDocument createSupplierPayment(SettlementDraft input, long actorId) {
        return create(input, new PaymentCreator(), AccountType.LIABILITY, actorId);
    }

    @Override
    public VoucherDocument editCustomerReceipt(UUID id, long version, SettlementDraft input, long actorId) {
        return edit(id, version, input, new ReceiptCreator(), AccountType.ASSET, actorId);
    }

    @Override
    public VoucherDocument editSupplierPayment(UUID id, long version, SettlementDraft input, long actorId) {
        return edit(id, version, input, new PaymentCreator(), AccountType.LIABILITY, actorId);
    }

    private VoucherDocument create(SettlementDraft input, VoucherCreator creator, AccountType type, long actorId) {
        return transactions.execute(session -> {
            requireActor(session, actorId, Permission.CREATE_VOUCHER);
            Draft draft = resolve(session, input, type);
            VoucherValidator validator = Objects.requireNonNull(validators.create(session), "Validator is required");
            VoucherWorkflowService workflow = new VoucherWorkflowService(
                    session.users(), session.documents(), validator, clock);
            VoucherDocument document = workflow.create(creator, draft, actorId);
            validator.validate(document.getContent());
            return document;
        });
    }

    private VoucherDocument edit(UUID id, long version, SettlementDraft input, VoucherCreator creator,
                                  AccountType type, long actorId) {
        return transactions.execute(session -> {
            requireActor(session, actorId, Permission.EDIT_VOUCHER);
            Draft draft = resolve(session, input, type);
            VoucherValidator validator = Objects.requireNonNull(validators.create(session), "Validator is required");
            VoucherWorkflowService workflow = new VoucherWorkflowService(
                    session.users(), session.documents(), validator, clock);
            VoucherDocument document = workflow.edit(id, version, creator, draft, actorId);
            validator.validate(document.getContent());
            return document;
        });
    }

    private Draft resolve(AccountingSession session, SettlementDraft input, AccountType type) {
        Objects.requireNonNull(input, "Settlement input is required");
        Account debt = session.accounts().findByCode(input.getDebtAccountCode()).orElseThrow(
                () -> new IllegalArgumentException("Debt account not found: " + input.getDebtAccountCode()));
        OpenItemBook.requireDebtAccount(debt);
        if (debt.getType() != type) {
            throw new IllegalArgumentException("Customer collections require receivables; supplier payments require payables");
        }
        MoneyAccount fund = session.moneyAccounts().findByCode(input.getMoneyAccountCode()).orElseThrow(
                () -> new IllegalArgumentException("Money account not found: " + input.getMoneyAccountCode()));
        return type == AccountType.ASSET
                ? input.toReceiptDraft(fund.getLedgerAccountCode()) : input.toPaymentDraft(fund.getLedgerAccountCode());
    }

    private void requireActor(AccountingSession session, long actorId, Permission permission) {
        session.users().findById(actorId).orElseThrow(() -> new AccessDeniedException("Unknown user: " + actorId))
                .requirePermission(permission);
    }
}
