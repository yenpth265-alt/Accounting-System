package vn.ketoan;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.CorrectionService;
import vn.ketoan.application.service.PrepaidExpenseService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.PrepaidPlanRequest;
import vn.ketoan.domain.StraightLineAllocation;
import vn.ketoan.domain.VoucherDocument;

import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.SettlementTestData.key;
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.account;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;

public final class CorrectionTestData {

    private CorrectionTestData() { }

    public static void seedPrepaid(UnitOfWork transactions) {
        transactions.execute(session -> {
            session.accounts().insert(account("242", AccountType.ASSET, AccountTracking.NONE));
            session.periods().insert(AccountingPeriod.open(YearMonth.from(DATE).plusMonths(1)));
            session.periods().insert(AccountingPeriod.open(YearMonth.from(DATE).plusMonths(2)));
            return null;
        });
    }

    public static CorrectionService corrections(UnitOfWork transactions) {
        return new CorrectionService(transactions, validators(), CLOCK);
    }

    public static PrepaidExpenseService prepaid(UnitOfWork transactions) {
        return new PrepaidExpenseService(transactions, validators(), CLOCK, new StraightLineAllocation());
    }

    public static JournalEntry prepaidSource(UnitOfWork transactions, String reference, long amount) {
        VoucherDocument draft = transactions.execute(session -> workflow(session).create(new GeneralCreator(),
                new Draft(reference, DATE, DATE, "Prepaid service acquired on credit", List.of(
                        debit("242", amount, null, null), credit("331", amount, "NCC01", null))), OWNER));
        return postDraft(transactions, draft);
    }

    public static PrepaidPlanRequest plan(JournalEntry source, String reference, int months) {
        return new PrepaidPlanRequest(UUID.randomUUID(), reference, key(source, "242"), "6427", YearMonth.from(DATE), months);
    }

    public static VoucherDocument approve(UnitOfWork transactions, VoucherDocument draft) {
        return transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument pending = workflow.submit(draft.getId(), draft.getVersion(), OWNER);
            return workflow.approve(pending.getId(), pending.getVersion(), CHIEF);
        });
    }
}
