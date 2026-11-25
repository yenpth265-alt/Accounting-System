package vn.ketoan;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.ClosingService;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ExpenseCreator;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.value.Money;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.OWNER;

public final class ClosingTestData {
    private ClosingTestData() { }
    public static void seed(UnitOfWork unit) {
        unit.execute(session -> {
            session.accounts().insert(VoucherTestData.account("911", AccountType.RESULT, AccountTracking.NONE));
            session.accounts().insert(VoucherTestData.account("4212", AccountType.EQUITY, AccountTracking.NONE));
            return null;
        });
    }
    public static ClosingService closing(UnitOfWork unit) { return new ClosingService(unit, CLOCK); }
    public static ClosingRequest request(String reference) {
        return new ClosingRequest(UUID.randomUUID(), reference, YearMonth.from(DATE), "911", "4212");
    }
    public static JournalEntry expense(UnitOfWork unit, String reference, long amount) {
        var draft = unit.execute(session -> workflow(session).create(new ExpenseCreator(), new Draft(reference, DATE, DATE,
                "Recognized supplier service", List.of(VoucherLine.debit("6427", Money.of(amount), null, null),
                VoucherLine.credit("331", Money.of(amount), "NCC01", null))), OWNER));
        return postDraft(unit, draft);
    }
    public static JournalEntry general(UnitOfWork unit, String reference, VoucherLine... lines) {
        var draft = unit.execute(session -> workflow(session).create(new GeneralCreator(), new Draft(reference, DATE, DATE,
                "Test ordinary entry", List.of(lines)), OWNER));
        return postDraft(unit, draft);
    }
}
