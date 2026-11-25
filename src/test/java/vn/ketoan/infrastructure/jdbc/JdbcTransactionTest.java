package vn.ketoan.infrastructure.jdbc;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.DebtAllocationRepository;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.DebtService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.approved;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.SettlementTestData.allocations;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;

class JdbcTransactionTest {

    private JdbcTestDatabase database;
    private UnitOfWork transactions;

    @BeforeEach
    void setUp() throws Exception {
        database = new JdbcTestDatabase();
        database.migrateAndSeed();
        transactions = database.transactions();
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void failureAfterJournalDocumentAndHistoryWritesRollsEverythingBack() {
        VoucherDocument approved = approved(transactions, new ReceiptCreator(), vn.ketoan.VoucherTestData.receipt());
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            var actor = session.users().findById(CHIEF).orElseThrow();
            session.journal().append(JournalEntry.fromApproved(UUID.randomUUID(), approved, actor, TIME));
            session.documents().update(approved.markPosted(actor, TIME), approved.getVersion());
            session.accounts().update(session.accounts().findByCode("1111").orElseThrow().deactivate());
            throw new IllegalStateException("Simulated interruption before commit");
        }));
        VoucherDocument stored = database.transactions().execute(session -> session.documents().findById(approved.getId()).orElseThrow());
        assertEquals(VoucherStatus.APPROVED, stored.getStatus());
        assertEquals(approved.getHistory(), stored.getHistory());
        assertTrue(new LedgerService(database.transactions()).entries(VIEWER).isEmpty());
        assertTrue(database.transactions().<Boolean>execute(session -> session.accounts().findByCode("1111").orElseThrow().isActive()));
    }

    @Test
    void integrityCheckRejectsEitherHalfOfAnIncompletePosting() {
        VoucherDocument approved = approved(transactions, new ReceiptCreator(), vn.ketoan.VoucherTestData.receipt());
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.journal().append(JournalEntry.fromApproved(UUID.randomUUID(), approved,
                    session.users().findById(CHIEF).orElseThrow(), TIME));
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.documents().update(approved.markPosted(session.users().findById(CHIEF).orElseThrow(), TIME), approved.getVersion());
            return null;
        }));
        assertEquals(0, new LedgerService(database.transactions()).entries(VIEWER).size());
        assertEquals(approved.getVersion(), database.transactions().execute(session ->
                session.documents().findById(approved.getId()).orElseThrow()).getVersion());
    }

    @Test
    void caughtSqlFailureStillDiscardsEarlierCatalogWrites() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            var account = session.accounts().findByCode("1111").orElseThrow();
            session.accounts().update(account.deactivate());
            try { session.accounts().insert(account); }
            catch (JdbcPersistenceException expected) { /* The transaction must remain rollback-only. */ }
            return null;
        }));
        assertTrue(database.transactions().<Boolean>execute(session -> session.accounts().findByCode("1111").orElseThrow().isActive()));
    }

    @Test
    void nestedTransactionsAcrossInstancesCannotCommitTheOuterWrites() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("1111").orElseThrow().deactivate());
            try { database.transactions().execute(other -> null); }
            catch (IllegalStateException expected) { /* Catching the error cannot make the outer transaction commit. */ }
            return null;
        }));
        assertTrue(database.transactions().<Boolean>execute(session -> session.accounts().findByCode("1111").orElseThrow().isActive()));
    }

    @Test
    void sessionsAndRepositoriesCannotEscapeTheirConnectionOrCallingThread() throws Exception {
        AtomicReference<AccountingSession> escaped = new AtomicReference<>();
        AtomicReference<DebtAllocationRepository> repository = new AtomicReference<>();
        transactions.execute(session -> {
            escaped.set(session);
            repository.set(session.allocations());
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Boolean> blocked = executor.submit(() -> {
                    try { session.users().findAll(); return false; }
                    catch (IllegalStateException expected) { return true; }
                });
                assertTrue(blocked.get(10, TimeUnit.SECONDS));
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            return null;
        });
        assertThrows(IllegalStateException.class, () -> escaped.get().journal());
        assertThrows(IllegalStateException.class, () -> repository.get().findAll());
    }

    @Test
    void allocationAndCancellationWritesRollBackTogetherWithOtherChanges() {
        JournalEntry sale = sale(transactions, "DT001", 10);
        JournalEntry receipt = receipt(transactions, "PT001", 4);
        var request = request(sale, receipt, 4);
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.allocations().append(new DebtAllocation(request, CHIEF, TIME));
            throw new IllegalStateException("Interrupted allocation");
        }));
        assertTrue(new DebtService(database.transactions()).allocations(VIEWER).isEmpty());
        allocations(transactions).allocate(request, CHIEF);
        assertThrows(AssertionError.class, () -> transactions.execute(session -> {
            session.allocations().appendCancellation(new AllocationCancellation(request.id(), DATE, "Wrong match", CHIEF, TIME));
            throw new AssertionError("Interrupted cancellation");
        }));
        assertTrue(new DebtService(database.transactions()).cancellations(VIEWER).isEmpty());
        assertEquals(Money.of(6), new DebtService(database.transactions()).statement("131", "KH01", DATE, VIEWER).unsettledDebt());
    }

    @Test
    void concurrentConnectionsPostTheSameVoucherOnlyOnce() throws Exception {
        VoucherDocument approved = approved(transactions, new ReceiptCreator(), vn.ketoan.VoucherTestData.receipt());
        Callable<UUID> task = () -> posting(database.transactions()).post(approved.getId(), approved.getVersion(), CHIEF).getId();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> first = executor.submit(task);
            Future<UUID> second = executor.submit(task);
            assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, new LedgerService(database.transactions()).entries(VIEWER).size());
        assertEquals(4, database.transactions().execute(session ->
                session.documents().findById(approved.getId()).orElseThrow()).getHistory().size());
    }

    @Test
    void concurrentConnectionsCannotAllocateMoreThanTheAvailableSettlement() throws Exception {
        JournalEntry sale = sale(transactions, "DT001", 10);
        JournalEntry receipt = receipt(transactions, "PT001", 4);
        Callable<Boolean> task = () -> {
            try { allocations(database.transactions()).allocate(request(sale, receipt, 3), CHIEF); return true; }
            catch (IllegalArgumentException expected) { return false; }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, new DebtService(database.transactions()).allocations(VIEWER).size());
        assertEquals(Money.of(1), new DebtService(database.transactions()).statement("131", "KH01", DATE, VIEWER).unallocatedSettlements());
    }
}
