package vn.ketoan.infrastructure.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.CorrectionPostingPolicy;
import vn.ketoan.application.service.DebtService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.PrepaidExpenseService;
import vn.ketoan.application.service.PrepaidPostingPolicy;
import vn.ketoan.demo.CorrectionDemo;
import vn.ketoan.demo.PrepaidDemo;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.PrepaidAllocationRequest;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;

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
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.SettlementTestData.allocations;
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class JdbcCorrectionPrepaidTest {

    private static final YearMonth FIRST = YearMonth.from(DATE);
    private JdbcTestDatabase database;
    private UnitOfWork transactions;

    @BeforeEach
    void setUp() throws Exception {
        database = new JdbcTestDatabase();
        database.migrateAndSeed();
        transactions = database.transactions();
        seedPrepaid(transactions);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) { database.close(); }
    }

    @Test
    void reversalSourceLinkAuditAndAutomaticMatchingSurviveReconnection() {
        JournalEntry sale = sale(transactions, "DT001", 10_000_000);
        JournalEntry receipt = receipt(transactions, "PT001", 4_000_000);
        var allocation = allocations(transactions).allocate(request(sale, receipt, 4_000_000), CHIEF);
        VoucherDocument reversal = approve(transactions, corrections(transactions).createReversal(new ReversalRequest(
                UUID.randomUUID(), sale.getSourceDocumentId(), "DAO001", DATE, "Hủy ghi nhận dịch vụ 'sai'"), OWNER));
        assertThrows(IllegalStateException.class, () -> posting(transactions).post(reversal.getId(), reversal.getVersion(), CHIEF));
        allocations(transactions).cancel(allocation.id(), DATE, "Undo invoice allocation", CHIEF);
        posting(transactions).post(reversal.getId(), reversal.getVersion(), CHIEF);
        UnitOfWork fresh = database.transactions();
        var link = corrections(fresh).history(VIEWER).getFirst();
        assertEquals(sale.getSourceDocumentId(), link.sourceDocumentId());
        assertEquals(reversal.getId(), link.targetDocumentId());
        assertEquals("Hủy ghi nhận dịch vụ 'sai'", link.reason());
        assertEquals(VoucherStatus.POSTED, fresh.execute(session -> session.documents().findById(sale.getSourceDocumentId()).orElseThrow()).getStatus());
        var debt = new DebtService(fresh).statement("131", "KH01", DATE, VIEWER);
        assertEquals(Money.ZERO, debt.unsettledDebt());
        assertEquals(Money.of(4_000_000), debt.unallocatedSettlements());
        assertEquals(2, new DebtService(fresh).allocations(VIEWER).size());
        var automatic = new DebtService(fresh).allocations(VIEWER).stream().filter(value -> !value.id().equals(allocation.id())).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class, () -> allocations(fresh).cancel(automatic.id(), DATE, "Invalid unlink", CHIEF));
        assertEquals(1, new DebtService(database.transactions()).cancellations(VIEWER).size());
    }

    @Test
    void frozenScheduleExpenseReversalAndCancellationArePersistedAcrossConnections() {
        JournalEntry source = prepaidSource(transactions, "SOURCE", 100);
        var plan = prepaid(transactions).createPlan(plan(source, "PLAN001", 3), OWNER);
        assertEquals(List.of(Money.of(33), Money.of(33), Money.of(34)), plan.schedule().stream().map(value -> value.amount()).toList());
        postDraft(transactions, prepaid(transactions).createInstallment(installment(plan.id(), "PB11", FIRST), OWNER));
        var december = prepaid(transactions).createInstallment(installment(plan.id(), "PB12", FIRST.plusMonths(1)), OWNER);
        postDraft(transactions, december);
        postDraft(transactions, corrections(transactions).createReversal(new ReversalRequest(UUID.randomUUID(), december.getId(),
                "REV12", FIRST.plusMonths(1).atEndOfMonth(), "Correct monthly allocation"), OWNER));
        var reopened = prepaid(database.transactions());
        assertEquals(plan, reopened.plans(VIEWER).getFirst());
        assertEquals(Money.of(33), reopened.statement(plan.id(), FIRST.plusMonths(1).atEndOfMonth(), VIEWER).recognized());
        assertEquals(Money.of(67), reopened.statement(plan.id(), FIRST.plusMonths(1).atEndOfMonth(), VIEWER).remainingAsset());
        postDraft(transactions, reopened.createInstallment(installment(plan.id(), "REDO12", FIRST.plusMonths(1)), OWNER));
        postDraft(transactions, reopened.createInstallment(installment(plan.id(), "PB01", FIRST.plusMonths(2)), OWNER));
        assertEquals(Money.of(100), reopened.statement(plan.id(), FIRST.plusMonths(2).atEndOfMonth(), VIEWER).recognized());
        assertEquals(Money.ZERO, reopened.statement(plan.id(), FIRST.plusMonths(2).atEndOfMonth(), VIEWER).remainingAsset());
        var cancellation = reopened.cancelPlan(plan.id(), FIRST.plusMonths(2).atEndOfMonth(), "Schedule completed", CHIEF);
        assertEquals(cancellation, database.transactions().execute(session -> session.prepaidPlans().findCancellation(plan.id()).orElseThrow()));
        assertTrue(prepaid(database.transactions()).statement(plan.id(), FIRST.plusMonths(2).atEndOfMonth(), VIEWER).cancelled());
    }

    @Test
    void aFailureAfterAutomaticMatchingRollsBackTheSqlJournalAndHistoryTogether() {
        JournalEntry sale = sale(transactions, "DT001", 10);
        VoucherDocument reversal = approve(transactions, corrections(transactions).createReversal(new ReversalRequest(
                UUID.randomUUID(), sale.getSourceDocumentId(), "DAO001", DATE, "Rollback test"), OWNER));
        PostingPolicy failure = new PostingPolicy() {
            @Override public void beforePost(AccountingSession session, VoucherDocument approved) { }
            @Override public void afterPost(AccountingSession session, JournalEntry entry) {
                assertEquals(1, session.allocations().findAll().size());
                throw new IllegalStateException("Interrupted after SQL matching");
            }
        };
        PostingService posting = new PostingService(transactions, validators(), CLOCK,
                List.of(new CorrectionPostingPolicy(), new PrepaidPostingPolicy(), failure));
        assertThrows(IllegalStateException.class, () -> posting.post(reversal.getId(), reversal.getVersion(), CHIEF));
        assertEquals(1, new LedgerService(database.transactions()).entries(VIEWER).size());
        assertTrue(new DebtService(database.transactions()).allocations(VIEWER).isEmpty());
        assertEquals(reversal.getHistory(), database.transactions().execute(session ->
                session.documents().findById(reversal.getId()).orElseThrow()).getHistory());
        posting(database.transactions()).post(reversal.getId(), reversal.getVersion(), CHIEF);
    }

    @Test
    void twoConnectionsCannotReserveTheSameSourceOrCreateTwoLiveInstallmentsForOneMonth() throws Exception {
        JournalEntry source = prepaidSource(transactions, "SOURCE", 6_000_000);
        Callable<Boolean> reserve = () -> {
            try {
                prepaid(database.transactions()).createPlan(plan(source, "PLAN_" + UUID.randomUUID().toString().substring(0, 8), 3), OWNER);
                return true;
            } catch (IllegalStateException expected) { return false; }
        };
        assertOneWinner(reserve);
        var plan = prepaid(database.transactions()).plans(VIEWER).getFirst();
        Callable<Boolean> create = () -> {
            try {
                prepaid(database.transactions()).createInstallment(installment(plan.id(), "PB_" + UUID.randomUUID().toString().substring(0, 8), FIRST), OWNER);
                return true;
            } catch (IllegalStateException expected) { return false; }
        };
        assertOneWinner(create);
        assertEquals(1, prepaid(database.transactions()).plans(VIEWER).size());
        assertEquals(1, prepaid(database.transactions()).installments(plan.id(), VIEWER).size());
    }

    @Test
    void tamperingWithScheduleAmountsIsDetectedBeforeReturningAStatement() throws Exception {
        JournalEntry source = prepaidSource(transactions, "SOURCE", 6_000_000);
        var plan = prepaid(transactions).createPlan(plan(source, "PLAN001", 3), OWNER);
        database.execute("UPDATE acc_prepaid_schedule SET amount=amount+1 WHERE month_key='2026-11'");
        assertThrows(IllegalArgumentException.class, () -> prepaid(database.transactions()).statement(plan.id(), DATE, VIEWER));
    }

    @Test
    void invalidStrategyRollsBackBothPlanHeaderAndAllScheduleRows() {
        JournalEntry source = prepaidSource(transactions, "SOURCE", 6_000_000);
        PrepaidExpenseService broken = new PrepaidExpenseService(transactions, validators(), CLOCK,
                (total, months) -> List.of(Money.of(1), Money.of(1), Money.of(1)));
        assertThrows(IllegalArgumentException.class, () -> broken.createPlan(plan(source, "PLAN001", 3), OWNER));
        assertTrue(prepaid(database.transactions()).plans(VIEWER).isEmpty());
        assertEquals(Money.of(6_000_000), new LedgerService(database.transactions()).netBalance("242", DATE, VIEWER));
    }

    @Test
    void persistentDemosCanBeRepeatedWithoutDuplicatePostingsOrChangingExistingCustomerDebt() {
        sale(transactions, "EXISTING_SALE", 10_000_000);
        receipt(transactions, "EXISTING_RECEIPT", 4_000_000);
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) {
            CorrectionDemo.run(output, database.transactions(), CLOCK);
            PrepaidDemo.run(output, database.transactions(), CLOCK);
            var before = new LedgerService(database.transactions()).entries(VIEWER).stream().map(JournalEntry::getId).toList();
            assertEquals(14, before.size());
            CorrectionDemo.run(output, database.transactions(), CLOCK);
            PrepaidDemo.run(output, database.transactions(), CLOCK);
            assertEquals(before, new LedgerService(database.transactions()).entries(VIEWER).stream().map(JournalEntry::getId).toList());
        }
        assertEquals(Money.of(6_000_000), new DebtService(database.transactions())
                .statement("131", "KH01", LocalDate.of(2026, 11, 25), VIEWER).netDebt());
        assertEquals(4, corrections(database.transactions()).history(VIEWER).size());
        var plan = prepaid(database.transactions()).plans(VIEWER).getFirst();
        assertEquals(4, prepaid(database.transactions()).installments(plan.id(), VIEWER).size());
        var statement = prepaid(database.transactions()).statement(plan.id(), LocalDate.of(2027, 1, 31), VIEWER);
        assertEquals(Money.of(6_000_000), statement.recognized());
        assertEquals(Money.ZERO, statement.remainingAsset());
    }

    private PrepaidAllocationRequest installment(UUID planId, String reference, YearMonth month) {
        return new PrepaidAllocationRequest(UUID.randomUUID(), planId, reference, month.atEndOfMonth());
    }

    private void assertOneWinner(Callable<Boolean> work) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(work);
            Future<Boolean> second = executor.submit(work);
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
        }
    }
}
