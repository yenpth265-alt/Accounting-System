package vn.ketoan.infrastructure.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.ClosingPostingPolicy;
import vn.ketoan.application.service.ClosingService;
import vn.ketoan.application.service.CorrectionPostingPolicy;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.PrepaidPostingPolicy;
import vn.ketoan.demo.ClosingDemo;
import vn.ketoan.demo.CorrectionDemo;
import vn.ketoan.demo.PrepaidDemo;
import vn.ketoan.domain.ClosingAction;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.PeriodStatus;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;
import static org.junit.jupiter.api.Assertions.*;
import static vn.ketoan.ClosingTestData.*;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class JdbcClosingTest {
    private JdbcTestDatabase database;
    private UnitOfWork unit;
    @BeforeEach void setUp() throws Exception {
        database = new JdbcTestDatabase();
        database.migrateAndSeed();
        unit = database.transactions();
        seed(unit);
    }
    @AfterEach void tearDown() throws Exception { if (database != null) { database.close(); } }

    @Test void preparationReviewAndAtomicCompletionSurviveDifferentSqlConnections() {
        sale(unit, "SALE", 10_000_000);
        expense(unit, "EXPENSE", 3_000_000);
        receipt(unit, "RECEIPT", 4_000_000);
        var request = request("KC001");
        var batch = closing(unit).prepare(request, OWNER);
        assertEquals(batch, closing(database.transactions()).batches(VIEWER).getFirst());
        closing(database.transactions()).approve(batch.id(), CHIEF);
        closing(database.transactions()).close(batch.id(), CHIEF);
        closing(database.transactions()).close(batch.id(), CHIEF);
        assertEquals(batch, closing(database.transactions()).prepare(request, OWNER));
        assertEquals(Money.of(-7_000_000), new LedgerService(database.transactions()).netBalance("4212", DATE.withDayOfMonth(30), VIEWER));
        assertEquals(Money.of(6_000_000), new LedgerService(database.transactions()).partnerNetBalance("131", "KH01", DATE.withDayOfMonth(30), VIEWER));
        assertEquals(6, new LedgerService(database.transactions()).entries(VIEWER).size());
        assertEquals(PeriodStatus.CLOSED, database.transactions().execute(session -> session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().getStatus()));
        assertEquals(2, closing(database.transactions()).history(batch.id(), VIEWER).size());
    }

    @Test void aFailureAfterTwoSqlPostingsLeavesEveryVoucherApprovedAndThePeriodUnclosed() {
        sale(unit, "SALE", 10);
        expense(unit, "EXPENSE", 3);
        var batch = closing(unit).prepare(request("FAILURE"), OWNER);
        closing(unit).approve(batch.id(), CHIEF);
        AtomicInteger calls = new AtomicInteger();
        PostingPolicy fail = new PostingPolicy() {
            @Override public void beforePost(AccountingSession session, VoucherDocument document) { }
            @Override public void afterPost(AccountingSession session, JournalEntry entry) {
                if (calls.incrementAndGet() == 2) { throw new IllegalStateException("Connection interrupted halfway through closing"); }
            }
        };
        var policies = new ArrayList<PostingPolicy>(List.of(new CorrectionPostingPolicy(), new PrepaidPostingPolicy(), new ClosingPostingPolicy()));
        policies.add(fail);
        assertThrows(IllegalStateException.class, () -> new ClosingService(unit, CLOCK, policies).close(batch.id(), CHIEF));
        assertEquals(2, new LedgerService(database.transactions()).entries(VIEWER).size());
        database.transactions().execute(session -> {
            assertEquals(PeriodStatus.CLOSING, session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().getStatus());
            for (UUID id : batch.documentIds()) { assertEquals(VoucherStatus.APPROVED, session.documents().findById(id).orElseThrow().getStatus()); }
            return null;
        });
        assertEquals(List.of(ClosingAction.APPROVED), closing(database.transactions()).history(batch.id(), VIEWER).stream().map(event -> event.action()).toList());
        closing(database.transactions()).close(batch.id(), CHIEF);
    }

    @Test void cancellationHistoryRetainsItsOldBasisAfterAdditionalOrdinaryPostingsAndANewClosing() {
        sale(unit, "SALE", 10);
        var old = closing(unit).prepare(request("OLD"), OWNER);
        closing(unit).approve(old.id(), CHIEF);
        closing(database.transactions()).cancel(old.id(), "Missing expense", CHIEF);
        expense(unit, "EXPENSE", 4);
        var current = closing(database.transactions()).prepare(request("NEW"), OWNER);
        closing(database.transactions()).approve(current.id(), CHIEF);
        closing(database.transactions()).close(current.id(), CHIEF);
        assertEquals(Money.of(10), old.profit());
        assertEquals(Money.of(6), current.profit());
        assertEquals(2, closing(database.transactions()).batches(VIEWER).size());
        assertEquals(2, closing(database.transactions()).history(old.id(), VIEWER).size());
    }

    @Test void twoConcurrentConnectionsCanPrepareOnlyOneLiveClosing() throws Exception {
        sale(unit, "SALE", 10);
        Callable<Boolean> work = () -> {
            try {
                closing(database.transactions()).prepare(request("KC_" + UUID.randomUUID().toString().substring(0, 8)), OWNER);
                return true;
            } catch (IllegalStateException expected) { return false; }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(work);
            var second = pool.submit(work);
            assertEquals(1, (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, closing(database.transactions()).batches(VIEWER).size());
    }

    @Test void modifiedSourceChecksumIsDetectedBeforeReturningStoredData() throws Exception {
        sale(unit, "SALE", 10);
        closing(unit).prepare(request("KC"), OWNER);
        database.execute("UPDATE acc_closing_batches SET source_checksum=REPEAT('0',64)");
        assertThrows(IllegalArgumentException.class, () -> closing(database.transactions()).batches(VIEWER));
    }

    @Test void aChangedClosingDecisionActorCannotDisagreeWithVoucherHistory() throws Exception {
        sale(unit, "SALE", 10);
        var batch = closing(unit).prepare(request("KC"), OWNER);
        closing(unit).approve(batch.id(), CHIEF);
        database.execute("UPDATE acc_closing_events SET actor_id=3 WHERE action='APPROVED'");
        assertThrows(IllegalStateException.class, () -> closing(database.transactions()).history(batch.id(), VIEWER));
    }

    @Test void allPersistentDemosCoexistAndCanBeRepeatedAfterTheMonthIsClosed() {
        sale(unit, "EXISTING_SALE", 10_000_000);
        receipt(unit, "EXISTING_RECEIPT", 4_000_000);
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) {
            CorrectionDemo.run(output, database.transactions(), CLOCK);
            PrepaidDemo.run(output, database.transactions(), CLOCK);
            ClosingDemo.run(output, database.transactions(), CLOCK);
            var before = new LedgerService(database.transactions()).entries(VIEWER).stream().map(JournalEntry::getId).toList();
            CorrectionDemo.run(output, database.transactions(), CLOCK);
            PrepaidDemo.run(output, database.transactions(), CLOCK);
            ClosingDemo.run(output, database.transactions(), CLOCK);
            assertEquals(before, new LedgerService(database.transactions()).entries(VIEWER).stream().map(JournalEntry::getId).toList());
        }
        var batch = closing(database.transactions()).batches(VIEWER).getFirst();
        assertEquals(Money.of(8_000_000), batch.profit());
        assertEquals(Money.ZERO, new LedgerService(database.transactions()).netBalance("5113", DATE.withDayOfMonth(30), VIEWER));
        assertEquals(Money.of(6_000_000), new LedgerService(database.transactions()).partnerNetBalance("131", "KH01", DATE.withDayOfMonth(30), VIEWER));
    }

    @Test void theClosingDemoAlsoCreatesACompleteSampleFromAnEmptyJournal() {
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) { ClosingDemo.run(output, database.transactions(), CLOCK); }
        var batch = closing(database.transactions()).batches(VIEWER).getFirst();
        assertEquals(Money.of(7_000_000), batch.profit());
        assertEquals(3, batch.documentIds().size());
    }

    @Test void closingRepositoriesStayScopedAndCaughtLookupFailuresMakeTheSqlTransactionRollbackOnly() {
        var escaped = unit.execute(AccountingSession::closings);
        assertThrows(IllegalStateException.class, escaped::findAll);
        assertThrows(IllegalStateException.class, () -> escaped.findByDocumentId(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> unit.execute(session -> {
            session.periods().insert(vn.ketoan.domain.AccountingPeriod.open(YearMonth.from(DATE).plusMonths(1)));
            try { session.closings().findByDocumentId(null); } catch (NullPointerException expected) { }
            return null;
        }));
        assertTrue(database.transactions().<Boolean>execute(session -> session.periods().findByMonth(YearMonth.from(DATE).plusMonths(1)).isEmpty()));
    }
}
