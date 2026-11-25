package vn.ketoan.application.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AdjustmentRequest;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLineKey;
import vn.ketoan.domain.PrepaidAllocationRequest;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidPlanRequest;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.CorrectionTestData.approve;
import static vn.ketoan.CorrectionTestData.corrections;
import static vn.ketoan.CorrectionTestData.plan;
import static vn.ketoan.CorrectionTestData.prepaid;
import static vn.ketoan.CorrectionTestData.prepaidSource;
import static vn.ketoan.CorrectionTestData.seedPrepaid;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class PrepaidExpenseServiceTest {

    private static final YearMonth FIRST = YearMonth.from(DATE);
    private InMemoryUnitOfWork transactions;
    private PrepaidExpenseService prepaid;
    private JournalEntry source;

    @BeforeEach
    void setUp() {
        transactions = transactions();
        seedPrepaid(transactions);
        prepaid = prepaid(transactions);
        source = prepaidSource(transactions, "SOURCE", 6_000_000);
    }

    @Test
    void recognizesOnlyPostedInstallmentsAndPreservesHistoricalAssetBalances() {
        PrepaidExpensePlan plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        assertEquals(List.of(Money.of(2_000_000), Money.of(2_000_000), Money.of(2_000_000)),
                plan.schedule().stream().map(value -> value.amount()).toList());
        VoucherDocument november = prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        assertEquals(Money.ZERO, recognized(FIRST.atEndOfMonth()));
        VoucherDocument approved = approve(transactions, november);
        assertEquals(Money.ZERO, recognized(FIRST.atEndOfMonth()));
        posting(transactions).post(approved.getId(), approved.getVersion(), CHIEF);
        assertEquals(Money.of(2_000_000), recognized(FIRST.atEndOfMonth()));
        assertEquals(Money.of(4_000_000), prepaid.statement(plan.id(), FIRST.atEndOfMonth(), VIEWER).remainingAsset());
        postDraft(transactions, prepaid.createInstallment(request(plan.id(), "PB12", FIRST.plusMonths(1)), OWNER));
        postDraft(transactions, prepaid.createInstallment(request(plan.id(), "PB01", FIRST.plusMonths(2)), OWNER));
        var statement = prepaid.statement(plan.id(), FIRST.plusMonths(2).atEndOfMonth(), VIEWER);
        assertEquals(Money.of(6_000_000), statement.recognized());
        assertEquals(Money.ZERO, statement.remainingAsset());
        assertEquals(Money.of(2_000_000), prepaid.statement(plan.id(), FIRST.atEndOfMonth(), VIEWER).recognized());
        assertEquals(Money.of(6_000_000), prepaid.statement(plan.id(), DATE, VIEWER).remainingAsset());
        assertEquals(Money.of(-6_000_000), new LedgerService(transactions).partnerNetBalance("331", "NCC01", DATE, VIEWER));
        assertThrows(UnsupportedOperationException.class, plan.schedule()::clear);
    }

    @Test
    void strategyIsDelegatedOnceAndItsScheduleIsFrozenForLaterRetries() {
        AtomicInteger calls = new AtomicInteger();
        PrepaidExpenseService custom = new PrepaidExpenseService(transactions, validators(), CLOCK, (amount, months) -> {
            calls.incrementAndGet();
            return List.of(Money.of(3_000_000), Money.of(1_000_000), Money.of(2_000_000));
        });
        var request = plan(source, "PLAN001", 3);
        var plan = custom.createPlan(request, OWNER);
        assertEquals(plan, custom.createPlan(request, OWNER));
        assertEquals(1, calls.get());
        VoucherDocument installment = custom.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        assertEquals(Money.of(3_000_000), installment.getContent().getTotalDebit());
        assertEquals(Money.of(6_000_000), plan.total());
    }

    @Test
    void invalidStrategiesAndInvalidSourceAccountDatesCannotLeavePlansBehind() {
        PrepaidExpenseService invalid = new PrepaidExpenseService(transactions, validators(), CLOCK,
                (amount, months) -> List.of(Money.of(1), Money.of(1), Money.of(1)));
        assertThrows(IllegalArgumentException.class, () -> invalid.createPlan(plan(source, "BAD", 3), OWNER));
        PrepaidPlanRequest original = plan(source, "PLAN001", 3);
        assertThrows(IllegalArgumentException.class, () -> prepaid.createPlan(new PrepaidPlanRequest(UUID.randomUUID(), "WRONG_ACCOUNT",
                original.source(), "5113", FIRST, 3), OWNER));
        assertThrows(IllegalArgumentException.class, () -> prepaid.createPlan(new PrepaidPlanRequest(UUID.randomUUID(), "EARLY",
                original.source(), "6427", FIRST.minusMonths(1), 3), OWNER));
        assertThrows(IllegalArgumentException.class, () -> prepaid.createPlan(new PrepaidPlanRequest(UUID.randomUUID(), "CREDIT_LINE",
                new JournalLineKey(source.getId(), 2), "6427", FIRST, 3), OWNER));
        assertTrue(prepaid.plans(VIEWER).isEmpty());
    }

    @Test
    void installmentRequestsAreIdempotentButAnotherLiveVoucherForTheSameMonthIsRejected() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var request = request(plan.id(), "PB11", FIRST);
        VoucherDocument first = prepaid.createInstallment(request, OWNER);
        assertEquals(first.getId(), prepaid.createInstallment(request, OWNER).getId());
        assertThrows(IllegalStateException.class, () -> prepaid.createInstallment(request(plan.id(), "OTHER11", FIRST), OWNER));
        assertThrows(IllegalArgumentException.class, () -> prepaid.createInstallment(new PrepaidAllocationRequest(
                request.id(), plan.id(), "CHANGED_REF", request.postingDate()), OWNER));
        transactions.execute(session -> workflow(session).cancel(first.getId(), 0, OWNER, "Replace draft"));
        VoucherDocument replacement = prepaid.createInstallment(request(plan.id(), "OTHER11", FIRST), OWNER);
        postDraft(transactions, replacement);
        assertEquals(2, prepaid.installments(plan.id(), VIEWER).size());
    }

    @Test
    void monthsMustBePostedInOrderAndFailedPostingDoesNotChangeJournalOrAudit() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var december = approve(transactions, prepaid.createInstallment(request(plan.id(), "PB12", FIRST.plusMonths(1)), OWNER));
        assertThrows(IllegalStateException.class, () -> posting(transactions).post(december.getId(), december.getVersion(), CHIEF));
        assertEquals(december.getHistory(), stored(december.getId()).getHistory());
        assertEquals(1, new LedgerService(transactions).entries(VIEWER).size());
        postDraft(transactions, prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER));
        posting(transactions).post(december.getId(), december.getVersion(), CHIEF);
        assertEquals(Money.of(4_000_000), recognized(FIRST.plusMonths(1).atEndOfMonth()));
    }

    @Test
    void generatedInstallmentCannotBeEditedToChangeTheAmountOrDate() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        VoucherDocument draft = prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        Draft invalid = new Draft("PB11", FIRST.atEndOfMonth(), FIRST.atEndOfMonth(), "Override the schedule", List.of(
                debit("6427", 3_000_000, null, null), credit("242", 3_000_000, null, null)));
        assertThrows(IllegalArgumentException.class, () -> transactions.execute(session ->
                workflow(session).edit(draft.getId(), 0, new GeneralCreator(), invalid, OWNER)));
        assertEquals(draft.getHistory(), stored(draft.getId()).getHistory());
        assertEquals(Money.of(2_000_000), stored(draft.getId()).getContent().getTotalDebit());
    }

    @Test
    void linkedDeltasCannotBypassTheFrozenSourceOrInstallmentAmounts() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var corrections = corrections(transactions);
        Draft sourceDelta = new Draft("DELTA_SOURCE", DATE, DATE, "Change prepaid source", List.of(
                debit("242", 1_000_000, null, null), credit("331", 1_000_000, "NCC01", null)));
        assertThrows(IllegalStateException.class, () -> corrections.createAdjustment(new AdjustmentRequest(UUID.randomUUID(),
                source.getSourceDocumentId(), "Cannot change a reserved source with a delta", sourceDelta), OWNER));
        var installment = prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        postDraft(transactions, installment);
        Draft expenseDelta = new Draft("DELTA_COST", FIRST.atEndOfMonth(), FIRST.atEndOfMonth(), "Change generated amount", List.of(
                debit("6427", 1_000_000, null, null), credit("242", 1_000_000, null, null)));
        assertThrows(IllegalStateException.class, () -> corrections.createAdjustment(new AdjustmentRequest(UUID.randomUUID(),
                installment.getId(), "Cannot change a frozen installment with a delta", expenseDelta), OWNER));
        assertTrue(corrections.history(VIEWER).isEmpty());
        assertEquals(Money.of(2_000_000), recognized(FIRST.atEndOfMonth()));
    }

    @Test
    void reversalsRestoreThePrepaidAssetAndRequireLaterMonthsToBeReversedFirst() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var november = prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        var december = prepaid.createInstallment(request(plan.id(), "PB12", FIRST.plusMonths(1)), OWNER);
        postDraft(transactions, november);
        postDraft(transactions, december);
        var corrections = corrections(transactions);
        assertThrows(IllegalStateException.class, () -> corrections.createReversal(new ReversalRequest(UUID.randomUUID(),
                november.getId(), "REV11", FIRST.plusMonths(1).atEndOfMonth(), "Reverse earlier month"), OWNER));
        postDraft(transactions, corrections.createReversal(new ReversalRequest(UUID.randomUUID(), december.getId(),
                "REV12", FIRST.plusMonths(1).atEndOfMonth(), "Correct December"), OWNER));
        assertEquals(Money.of(2_000_000), recognized(FIRST.plusMonths(1).atEndOfMonth()));
        assertEquals(Money.of(4_000_000), prepaid.statement(plan.id(), FIRST.plusMonths(1).atEndOfMonth(), VIEWER).remainingAsset());
        var january = approve(transactions, prepaid.createInstallment(request(plan.id(), "PB01", FIRST.plusMonths(2)), OWNER));
        assertThrows(IllegalStateException.class, () -> posting(transactions).post(january.getId(), january.getVersion(), CHIEF));
        postDraft(transactions, prepaid.createInstallment(request(plan.id(), "REDO12", FIRST.plusMonths(1)), OWNER));
        posting(transactions).post(january.getId(), january.getVersion(), CHIEF);
        assertEquals(Money.of(6_000_000), recognized(FIRST.plusMonths(2).atEndOfMonth()));
    }

    @Test
    void backdatedRedoCannotDoubleRecognizeAPeriodBeforeItsReversalTakesEffect() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var november = prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        postDraft(transactions, november);
        postDraft(transactions, corrections(transactions).createReversal(new ReversalRequest(UUID.randomUUID(),
                november.getId(), "REV11", FIRST.plusMonths(1).atDay(1), "Reverse next month"), OWNER));
        var redo = approve(transactions, prepaid.createInstallment(request(plan.id(), "REDO11", FIRST), OWNER));
        assertThrows(IllegalStateException.class, () -> posting(transactions).post(redo.getId(), redo.getVersion(), CHIEF));
        assertEquals(VoucherStatus.APPROVED, stored(redo.getId()).getStatus());
        assertEquals(Money.of(2_000_000), prepaid.statement(plan.id(), FIRST.atEndOfMonth(), VIEWER).recognized());
        assertEquals(Money.ZERO, prepaid.statement(plan.id(), FIRST.plusMonths(1).atDay(1), VIEWER).recognized());
    }

    @Test
    void planCancellationRequiresResolvingPendingWorkAndStopsNewInstallments() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var request = request(plan.id(), "PB11", FIRST);
        var draft = prepaid.createInstallment(request, OWNER);
        assertThrows(IllegalStateException.class, () -> prepaid.cancelPlan(plan.id(), DATE, "Stop schedule", OWNER));
        assertThrows(AccessDeniedException.class, () -> prepaid.cancelPlan(plan.id(), DATE, "Other owner", OTHER));
        transactions.execute(session -> workflow(session).cancel(draft.getId(), 0, OWNER, "Stop schedule"));
        var cancellation = prepaid.cancelPlan(plan.id(), DATE, "Stop schedule", CHIEF);
        assertEquals(cancellation, prepaid.cancelPlan(plan.id(), DATE, "Stop schedule", CHIEF));
        assertThrows(IllegalStateException.class, () -> prepaid.createInstallment(request(plan.id(), "PB12", FIRST.plusMonths(1)), OWNER));
        assertEquals(VoucherStatus.CANCELLED, prepaid.createInstallment(request, OWNER).getStatus());
        assertTrue(prepaid.statement(plan.id(), DATE, VIEWER).cancelled());
    }

    @Test
    void cancellingAndFullyReversingOldExpensesAllowsAReplacementPlanForTheSameSource() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var november = prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        postDraft(transactions, november);
        prepaid.cancelPlan(plan.id(), FIRST.atEndOfMonth(), "Reschedule", OWNER);
        assertThrows(IllegalStateException.class, () -> prepaid.createPlan(plan(source, "PLAN002", 3), OWNER));
        postDraft(transactions, corrections(transactions).createReversal(new ReversalRequest(UUID.randomUUID(), november.getId(),
                "REV11", FIRST.atEndOfMonth(), "Undo old schedule"), OWNER));
        var replacement = prepaid.createPlan(plan(source, "PLAN002", 3), OWNER);
        assertEquals(Money.of(6_000_000), replacement.total());
        assertEquals(2, prepaid.plans(VIEWER).size());
    }

    @Test
    void theSourceCanOnlyBeVoidedAfterStoppingThePlanAndReversingRecognizedExpenses() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        var november = prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER);
        postDraft(transactions, november);
        var corrections = corrections(transactions);
        ReversalRequest sourceReversal = new ReversalRequest(UUID.randomUUID(), source.getSourceDocumentId(), "REV_SOURCE",
                FIRST.atEndOfMonth(), "Void purchase");
        assertThrows(IllegalStateException.class, () -> corrections.createReversal(sourceReversal, OWNER));
        prepaid.cancelPlan(plan.id(), FIRST.atEndOfMonth(), "Purchase cancelled", OWNER);
        var draft = approve(transactions, corrections.createReversal(sourceReversal, OWNER));
        assertThrows(IllegalStateException.class, () -> posting(transactions).post(draft.getId(), draft.getVersion(), CHIEF));
        postDraft(transactions, corrections.createReversal(new ReversalRequest(UUID.randomUUID(), november.getId(),
                "REV11", FIRST.atEndOfMonth(), "Undo expense first"), OWNER));
        posting(transactions).post(draft.getId(), draft.getVersion(), CHIEF);
        assertEquals(Money.ZERO, recognized(FIRST.atEndOfMonth()));
        assertEquals(Money.ZERO, new LedgerService(transactions).netBalance("242", FIRST.atEndOfMonth(), VIEWER));
        assertEquals(Money.ZERO, prepaid.statement(plan.id(), FIRST.atEndOfMonth(), VIEWER).remainingAsset());
    }

    @ParameterizedTest
    @ValueSource(longs = {CHIEF, VIEWER, ADMIN, 999})
    void planAndInstallmentCreationRequireAccountingPermission(long actorId) {
        var request = plan(source, "PLAN001", 3);
        assertThrows(AccessDeniedException.class, () -> prepaid.createPlan(request, actorId));
        var plan = prepaid.createPlan(request, OWNER);
        assertThrows(AccessDeniedException.class, () -> prepaid.createInstallment(request(plan.id(), "PB11", FIRST), actorId));
    }

    @Test
    void planQueriesEnforceReadPermissionsAndCurrentInactiveAccountsBlockNewInstallments() {
        var plan = prepaid.createPlan(plan(source, "PLAN001", 3), OWNER);
        assertThrows(AccessDeniedException.class, () -> prepaid.statement(plan.id(), DATE, ADMIN));
        transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("6427").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class, () -> prepaid.createInstallment(request(plan.id(), "PB11", FIRST), OWNER));
        assertTrue(prepaid.installments(plan.id(), VIEWER).isEmpty());
    }

    private PrepaidAllocationRequest request(UUID planId, String reference, YearMonth month) {
        return new PrepaidAllocationRequest(UUID.randomUUID(), planId, reference, month.atEndOfMonth());
    }
    private Money recognized(LocalDate date) { return new LedgerService(transactions).netBalance("6427", date, VIEWER); }
    private VoucherDocument stored(UUID id) { return transactions.execute(session -> session.documents().findById(id).orElseThrow()); }
}
