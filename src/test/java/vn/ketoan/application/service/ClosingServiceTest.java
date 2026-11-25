package vn.ketoan.application.service;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.ClosingAction;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.PeriodStatus;
import vn.ketoan.domain.PrepaidAllocationRequest;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;
import static org.junit.jupiter.api.Assertions.*;
import static vn.ketoan.ClosingTestData.*;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.*;

class ClosingServiceTest {
    private InMemoryUnitOfWork unit;
    private ClosingService service;
    private static final YearMonth MONTH = YearMonth.from(DATE);
    @BeforeEach void setUp() { unit = transactions(); seed(unit); service = closing(unit); }

    @Test void previewDoesNotWriteAndClosingTransfersProfitWithoutChangingCashOrDebt() {
        sale(unit, "SALE", 10_000_000);
        expense(unit, "EXPENSE", 3_000_000);
        receipt(unit, "CASH", 4_000_000);
        var request = request("KC001");
        var preview = service.preview(request, VIEWER);
        assertEquals(Money.of(7_000_000), preview.profit());
        assertEquals(3, preview.vouchers().size());
        assertTrue(service.batches(VIEWER).isEmpty());
        ClosingBatch batch = service.prepare(request, OWNER);
        assertEquals(PeriodStatus.CLOSING, period());
        assertTrue(batch.documentIds().stream().allMatch(id -> stored(id).getStatus() == VoucherStatus.PENDING_APPROVAL));
        assertThrows(IllegalStateException.class, () -> service.close(batch.id(), CHIEF));
        service.approve(batch.id(), CHIEF);
        service.close(batch.id(), CHIEF);
        assertEquals(PeriodStatus.CLOSED, period());
        for (String account : List.of("5113", "6427", "911")) { assertEquals(Money.ZERO, balance(account)); }
        assertEquals(Money.of(-7_000_000), balance("4212"));
        assertEquals(Money.of(4_000_000), balance("1111"));
        assertEquals(Money.of(6_000_000), balance("131"));
        assertEquals(Money.of(-3_000_000), balance("331"));
        assertEquals(List.of(ClosingAction.APPROVED, ClosingAction.CLOSED), service.history(batch.id(), VIEWER).stream().map(event -> event.action()).toList());
    }

    @Test void lossIsTransferredToTheDebitOfRetainedEarnings() {
        sale(unit, "SALE", 2);
        expense(unit, "EXPENSE", 5);
        var batch = complete(request("LOSS"));
        assertEquals(Money.of(-3), batch.profit());
        assertEquals(Money.of(3), balance("4212"));
        assertEquals(Money.ZERO, balance("911"));
    }

    @Test void aBreakEvenMonthHasNoZeroAmountProfitVoucherAndAnEmptyMonthStillNeedsIndependentReview() {
        sale(unit, "SALE", 5);
        expense(unit, "EXPENSE", 5);
        assertEquals(2, complete(request("EVEN")).documentIds().size());
        unit.execute(session -> { session.periods().insert(AccountingPeriod.open(MONTH.plusMonths(1))); return null; });
        var next = new ClosingRequest(UUID.randomUUID(), "EMPTY", MONTH.plusMonths(1), "911", "4212");
        var batch = service.prepare(next, OWNER);
        assertTrue(batch.documentIds().isEmpty());
        assertThrows(IllegalStateException.class, () -> service.close(batch.id(), CHIEF));
        service.approve(batch.id(), CHIEF);
        service.close(batch.id(), CHIEF);
        assertEquals(2, service.history(batch.id(), VIEWER).size());
    }

    @Test void contraRevenueExpenseBalancesAndPartnerDetailsAreClosedWithTheCorrectSigns() {
        general(unit, "CONTRA", VoucherLine.debit("5113", Money.of(3), "KH01", null), VoucherLine.credit("6427", Money.of(3), "NCC01", null));
        var batch = complete(request("CONTRA_KC"));
        assertEquals(Money.of(-3), batch.revenue());
        assertEquals(Money.of(-3), batch.expense());
        assertEquals(Money.ZERO, batch.profit());
        assertEquals(Money.ZERO, balance("5113"));
        assertEquals(Money.ZERO, balance("6427"));
        assertTrue(stored(batch.documentIds().getFirst()).getContent().getLines().stream().anyMatch(line -> line.getPartnerCode().orElse("").equals("KH01")));
    }

    @Test void cancellingPreparedOrApprovedClosingRestoresTheOpenPeriodAndAllowsAFreshBasis() {
        sale(unit, "SALE", 10);
        var first = service.prepare(request("FIRST"), OWNER);
        service.cancel(first.id(), "Need another expense", CHIEF);
        assertEquals(PeriodStatus.OPEN, period());
        expense(unit, "LATE_EXPENSE", 3);
        var second = service.prepare(request("SECOND"), OWNER);
        service.approve(second.id(), CHIEF);
        service.cancel(second.id(), "Review again", CHIEF);
        assertTrue(second.documentIds().stream().allMatch(id -> stored(id).getStatus() == VoucherStatus.CANCELLED));
        assertEquals(Money.of(7), complete(request("FINAL")).profit());
        assertEquals(3, service.batches(VIEWER).size());
    }

    @Test void retriesAreIdempotentAndChangedRequestDetailsAreRejected() {
        sale(unit, "SALE", 10);
        var request = request("RETRY");
        var batch = service.prepare(request, OWNER);
        assertEquals(batch, service.prepare(request, OWNER));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(new ClosingRequest(request.id(), "OTHER", MONTH, "911", "4212"), OWNER));
        service.approve(batch.id(), CHIEF);
        service.approve(batch.id(), CHIEF);
        service.close(batch.id(), CHIEF);
        service.close(batch.id(), CHIEF);
        assertEquals(batch, service.prepare(request, OWNER));
        assertEquals(3, entries().size());
        assertEquals(2, service.history(batch.id(), VIEWER).size());
        assertThrows(IllegalStateException.class, () -> service.cancel(batch.id(), "Cannot undo locked closing", CHIEF));
    }

    @Test void pendingOrdinaryVouchersAndEarlierOpenPeriodsBlockPreparation() {
        var draft = unit.execute(session -> workflow(session).create(new GeneralCreator(), new Draft("PENDING", DATE, DATE, "Pending",
                List.of(VoucherLine.debit("1111", Money.of(1), null, "CASH01"), VoucherLine.credit("4111", Money.of(1), null, null))), OWNER));
        assertThrows(IllegalStateException.class, () -> service.prepare(request("BLOCKED"), OWNER));
        unit.execute(session -> { workflow(session).cancel(draft.getId(), draft.getVersion(), OWNER, "Unused"); session.periods().insert(AccountingPeriod.open(MONTH.minusMonths(1))); return null; });
        assertThrows(IllegalStateException.class, () -> service.prepare(request("EARLIER"), OWNER));
        assertTrue(service.batches(VIEWER).isEmpty());
        assertEquals(PeriodStatus.OPEN, period());
    }

    @Test void aDuePrepaidInstallmentMustBePostedBeforeClosing() {
        vn.ketoan.CorrectionTestData.seedPrepaid(unit);
        var source = vn.ketoan.CorrectionTestData.prepaidSource(unit, "PREPAID", 6);
        var prepaid = vn.ketoan.CorrectionTestData.prepaid(unit);
        var plan = prepaid.createPlan(vn.ketoan.CorrectionTestData.plan(source, "PLAN", 3), OWNER);
        assertThrows(IllegalStateException.class, () -> service.prepare(request("TOO_EARLY"), OWNER));
        var draft = prepaid.createInstallment(new PrepaidAllocationRequest(UUID.randomUUID(), plan.id(), "NOV", MONTH.atEndOfMonth()), OWNER);
        vn.ketoan.SettlementTestData.postDraft(unit, draft);
        assertEquals(Money.of(2), complete(request("READY")).expense());
    }

    @Test void ordinaryPostingAndIndividualClosingVoucherTransitionsCannotBypassTheBatchWorkflow() {
        sale(unit, "SALE", 10);
        var batch = service.prepare(request("FROZEN"), OWNER);
        UUID id = batch.documentIds().getFirst();
        assertThrows(IllegalStateException.class, () -> unit.execute(session -> workflow(session).returnForRevision(id, storedVersion(session, id), CHIEF, "Bypass")));
        service.approve(batch.id(), CHIEF);
        assertThrows(IllegalStateException.class, () -> posting(unit).post(id, stored(id).getVersion(), CHIEF));
        service.close(batch.id(), CHIEF);
        assertThrows(IllegalStateException.class, () -> unit.execute(session -> { session.periods().update(session.periods().findByMonth(MONTH).orElseThrow().reopen()); return null; }));
        assertEquals(PeriodStatus.CLOSED, period());
    }

    @Test void failingOnTheSecondClosingEntryRollsBackTheWholeBatchAndItsHistory() {
        sale(unit, "SALE", 10);
        expense(unit, "EXPENSE", 3);
        var batch = service.prepare(request("ROLLBACK"), OWNER);
        service.approve(batch.id(), CHIEF);
        AtomicInteger calls = new AtomicInteger();
        PostingPolicy failure = new PostingPolicy() {
            @Override public void beforePost(AccountingSession session, VoucherDocument approved) { }
            @Override public void afterPost(AccountingSession session, JournalEntry entry) {
                if (calls.incrementAndGet() == 2) { throw new IllegalStateException("Interrupted midway"); }
            }
        };
        var policies = new java.util.ArrayList<PostingPolicy>(PostingPolicies.standard());
        policies.add(failure);
        assertThrows(IllegalStateException.class, () -> new ClosingService(unit, CLOCK, policies).close(batch.id(), CHIEF));
        assertEquals(2, entries().size());
        assertEquals(PeriodStatus.CLOSING, period());
        assertTrue(batch.documentIds().stream().allMatch(id -> stored(id).getStatus() == VoucherStatus.APPROVED));
        assertEquals(1, service.history(batch.id(), VIEWER).size());
        service.close(batch.id(), CHIEF);
    }

    @Test void closedMonthCorrectionsAreRecognizedInTheNextOpenMonthAndCanBeClosedAgain() {
        var source = sale(unit, "SALE", 10);
        complete(request("NOV"));
        unit.execute(session -> { session.periods().insert(AccountingPeriod.open(MONTH.plusMonths(1))); return null; });
        var date = MONTH.plusMonths(1).atDay(2);
        var reversal = vn.ketoan.CorrectionTestData.corrections(unit).createReversal(new vn.ketoan.domain.ReversalRequest(
                UUID.randomUUID(), source.getSourceDocumentId(), "REV_NOV_IN_DEC", date, "Correct in next open month"), OWNER);
        vn.ketoan.SettlementTestData.postDraft(unit, reversal);
        var next = new ClosingRequest(UUID.randomUUID(), "DEC", MONTH.plusMonths(1), "911", "4212");
        var batch = complete(next);
        assertEquals(Money.of(-10), batch.profit());
        assertEquals(Money.ZERO, new LedgerService(unit).netBalance("4212", MONTH.plusMonths(1).atEndOfMonth(), VIEWER));
    }

    @Test void thePreparerCannotApproveEvenWhenTheyHaveBothRoles() {
        unit.execute(session -> { session.users().update(new User(OWNER, "user1", "Combined", Set.of(UserRole.ACCOUNTANT, UserRole.CHIEF))); return null; });
        var batch = service.prepare(request("INDEPENDENT"), OWNER);
        assertThrows(AccessDeniedException.class, () -> service.approve(batch.id(), OWNER));
        assertThrows(AccessDeniedException.class, () -> service.close(batch.id(), OWNER));
        assertThrows(AccessDeniedException.class, () -> service.cancel(batch.id(), "Self cancel", OWNER));
    }

    @ParameterizedTest @ValueSource(longs = {2, 4, 5}) void preparationRequiresAccountingPermission(long actorId) {
        assertThrows(AccessDeniedException.class, () -> service.prepare(request("DENIED"), actorId));
    }
    @ParameterizedTest @ValueSource(longs = {1, 4, 5}) void closingRequiresChiefPermission(long actorId) {
        var batch = service.prepare(request("DENIED"), OWNER);
        assertThrows(AccessDeniedException.class, () -> service.approve(batch.id(), actorId));
        assertThrows(AccessDeniedException.class, () -> service.close(batch.id(), actorId));
    }
    @Test void inactiveAccountsBlockPreparationAndApprovalWithoutLeavingPartialWrites() {
        sale(unit, "SALE", 10);
        unit.execute(session -> { session.accounts().update(session.accounts().findByCode("911").orElseThrow().deactivate()); return null; });
        assertThrows(IllegalStateException.class, () -> service.prepare(request("INACTIVE"), OWNER));
        assertTrue(service.batches(VIEWER).isEmpty());
        unit.execute(session -> { session.accounts().update(session.accounts().findByCode("911").orElseThrow().activate()); return null; });
        var batch = service.prepare(request("READY"), OWNER);
        unit.execute(session -> { session.accounts().update(session.accounts().findByCode("5113").orElseThrow().deactivate()); return null; });
        assertThrows(IllegalStateException.class, () -> service.approve(batch.id(), CHIEF));
        assertTrue(service.history(batch.id(), VIEWER).isEmpty());
    }

    @Test void aSecondLiveBatchAndInvalidAccountConfigurationAreRejectedWithoutExtraDocuments() {
        sale(unit, "SALE", 10);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(new ClosingRequest(UUID.randomUUID(), "WRONG", MONTH, "5113", "4212"), OWNER));
        assertEquals(1, entries().size());
        var batch = service.prepare(request("FIRST"), OWNER);
        assertThrows(IllegalStateException.class, () -> service.prepare(request("SECOND"), OWNER));
        assertEquals(1, service.batches(VIEWER).size());
        assertEquals(2, batch.documentIds().size());
    }

    @Test void unresolvedOpeningBalancesCannotBeSilentlyMovedIntoANewerMonth() {
        sale(unit, "SALE", 10);
        unit.execute(session -> {
            session.periods().update(session.periods().findByMonth(MONTH).orElseThrow().beginClosing().completeClosing());
            session.periods().insert(AccountingPeriod.open(MONTH.plusMonths(1)));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> service.prepare(new ClosingRequest(UUID.randomUUID(), "DEC", MONTH.plusMonths(1), "911", "4212"), OWNER));
        assertTrue(service.batches(VIEWER).isEmpty());
    }

    @Test void aNewUnresolvedDraftWhileClosingIsDetectedAgainBeforeReview() {
        sale(unit, "SALE", 10);
        var batch = service.prepare(request("FIRST"), OWNER);
        unit.execute(session -> workflow(session).create(new GeneralCreator(), new Draft("LATE_DRAFT", DATE, DATE, "Unresolved",
                List.of(VoucherLine.debit("1111", Money.of(1), null, "CASH01"), VoucherLine.credit("4111", Money.of(1), null, null))), OWNER));
        assertThrows(IllegalStateException.class, () -> service.approve(batch.id(), CHIEF));
        assertTrue(service.history(batch.id(), VIEWER).isEmpty());
        service.cancel(batch.id(), "Resolve new work first", CHIEF);
        assertEquals(PeriodStatus.OPEN, period());
    }

    @Test void manualResultPostingCannotBypassTheClosingWorkflow() {
        assertThrows(IllegalStateException.class, () -> general(unit, "MANUAL_RESULT",
                VoucherLine.debit("1111", Money.of(10), null, "CASH01"), VoucherLine.credit("911", Money.of(10), null, null)));
        assertTrue(entries().isEmpty());
    }

    @Test void generatedClosingVouchersCannotReceiveLinkedCorrectionsInALaterOpenPeriod() {
        sale(unit, "SALE", 10);
        var batch = complete(request("CLOSED"));
        unit.execute(session -> { session.periods().insert(AccountingPeriod.open(MONTH.plusMonths(1))); return null; });
        assertThrows(IllegalStateException.class, () -> vn.ketoan.CorrectionTestData.corrections(unit).createReversal(
                new vn.ketoan.domain.ReversalRequest(UUID.randomUUID(), batch.documentIds().getFirst(), "BYPASS_REV",
                        MONTH.plusMonths(1).atDay(1), "Invalid closing reversal"), OWNER));
        assertTrue(vn.ketoan.CorrectionTestData.corrections(unit).history(VIEWER).isEmpty());
    }

    @Test void caughtClosingRepositoryFailuresStillRollBackOtherChangesInTheSameTransaction() {
        assertThrows(IllegalStateException.class, () -> unit.execute(session -> {
            session.periods().insert(AccountingPeriod.open(MONTH.plusMonths(1)));
            try { session.closings().findByDocumentId(null); } catch (NullPointerException expected) { }
            return null;
        }));
        assertTrue(unit.<Boolean>execute(session -> session.periods().findByMonth(MONTH.plusMonths(1)).isEmpty()));
    }

    private ClosingBatch complete(ClosingRequest request) { var batch = service.prepare(request, OWNER); service.approve(batch.id(), CHIEF); return service.close(batch.id(), CHIEF); }
    private PeriodStatus period() { return unit.execute(session -> session.periods().findByMonth(MONTH).orElseThrow().getStatus()); }
    private Money balance(String code) { return new LedgerService(unit).netBalance(code, MONTH.atEndOfMonth(), VIEWER); }
    private VoucherDocument stored(UUID id) { return unit.execute(session -> session.documents().findById(id).orElseThrow()); }
    private long storedVersion(AccountingSession session, UUID id) { return session.documents().findById(id).orElseThrow().getVersion(); }
    private List<JournalEntry> entries() { return new LedgerService(unit).entries(VIEWER); }
}
