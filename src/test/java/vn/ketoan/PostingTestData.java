package vn.ketoan;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.COMBINED;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;
import static vn.ketoan.WorkflowTestData.user;

public final class PostingTestData {

    public static final Clock CLOCK = Clock.fixed(TIME, ZoneOffset.UTC);

    private PostingTestData() {
    }

    public static InMemoryUnitOfWork transactions() {
        InMemoryUnitOfWork transactions = new InMemoryUnitOfWork();
        seed(transactions);
        return transactions;
    }

    public static void seed(UnitOfWork transactions) {
        transactions.execute(session -> {
            VoucherTestData.accounts().forEach(session.accounts()::insert);
            VoucherTestData.partners().forEach(session.partners()::insert);
            VoucherTestData.moneyAccounts().forEach(session.moneyAccounts()::insert);
            session.periods().insert(AccountingPeriod.open(YearMonth.from(VoucherTestData.DATE)));
            session.users().insert(user(OWNER, UserRole.ACCOUNTANT));
            session.users().insert(user(CHIEF, UserRole.CHIEF));
            session.users().insert(user(OTHER, UserRole.ACCOUNTANT));
            session.users().insert(user(VIEWER, UserRole.VIEWER));
            session.users().insert(user(ADMIN, UserRole.ADMIN));
            session.users().insert(user(COMBINED, UserRole.ACCOUNTANT, UserRole.CHIEF));
            return null;
        });
    }

    public static VoucherValidatorFactory validators() {
        return session -> new VoucherValidationService(session.accounts(), session.partners(),
                session.moneyAccounts(), session.periods());
    }

    public static VoucherWorkflowService workflow(AccountingSession session) {
        return new VoucherWorkflowService(session.users(), session.documents(), validators().create(session), CLOCK);
    }

    public static Draft named(String reference, Draft original) {
        return new Draft(reference, original.getDocumentDate(), original.getPostingDate(),
                original.getDescription(), original.getLines());
    }

    public static VoucherDocument approved(UnitOfWork transactions, VoucherCreator creator, Draft input) {
        return transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument draft = workflow.create(creator, input, OWNER);
            VoucherDocument pending = workflow.submit(draft.getId(), 0, OWNER);
            return workflow.approve(pending.getId(), pending.getVersion(), CHIEF);
        });
    }

    public static PostingService posting(UnitOfWork transactions) {
        return new PostingService(transactions, validators(), CLOCK);
    }
}
