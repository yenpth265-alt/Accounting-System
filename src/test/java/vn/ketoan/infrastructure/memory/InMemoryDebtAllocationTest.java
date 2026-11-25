package vn.ketoan.infrastructure.memory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.DebtAllocationRepository;
import vn.ketoan.application.service.DebtService;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.SettlementTestData.allocations;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;

class InMemoryDebtAllocationTest {

    private InMemoryUnitOfWork transactions;
    private DebtAllocation allocation;

    @BeforeEach
    void setUp() {
        transactions = transactions();
        JournalEntry sale = sale(transactions, "DT001", 10);
        JournalEntry receipt = receipt(transactions, "PT001", 4);
        allocation = new DebtAllocation(request(sale, receipt, 4), CHIEF, TIME);
    }

    @Test
    void failureAfterAllocationWriteRollsBackAllocationAndOtherRepositoryChangesTogether() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.users().update(session.users().findById(OWNER).orElseThrow().deactivate());
            session.allocations().append(allocation);
            throw new IllegalStateException("Interrupted after allocation write");
        }));
        assertTrue(new DebtService(transactions).allocations(VIEWER).isEmpty());
        assertTrue(transactions.<Boolean>execute(session -> session.users().findById(OWNER).orElseThrow().isActive()));
    }

    @Test
    void commitChecksCapacityAndActorReferencesEvenForDirectRepositoryWrites() {
        AllocationRequest request = allocation.request();
        DebtAllocation tooLarge = new DebtAllocation(new AllocationRequest(request.id(),
                request.charge(), request.settlement(), Money.of(5), DATE, request.note()), CHIEF, TIME);
        assertThrows(IllegalArgumentException.class, () -> transactions.execute(session -> {
            session.allocations().append(tooLarge);
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.allocations().append(new DebtAllocation(request, 999, TIME));
            return null;
        }));
        assertTrue(new DebtService(transactions).allocations(VIEWER).isEmpty());
    }

    @Test
    void cancellationWriteRollsBackWithoutRemovingOriginalAllocation() {
        allocations(transactions).allocate(allocation.request(), CHIEF);
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.allocations().appendCancellation(new AllocationCancellation(allocation.id(), DATE, "Wrong match", CHIEF, TIME));
            throw new IllegalStateException("Interrupted after cancellation write");
        }));
        DebtService debts = new DebtService(transactions);
        assertEquals(1, debts.allocations(VIEWER).size());
        assertTrue(debts.cancellations(VIEWER).isEmpty());
        assertEquals(Money.of(6), debts.statement("131", "KH01", DATE, VIEWER).unsettledDebt());
    }

    @Test
    void repositoryHandlesStayScopedAndAppendHistoryCannotBeOverwritten() {
        AtomicReference<DebtAllocationRepository> escaped = new AtomicReference<>();
        transactions.execute(session -> {
            escaped.set(session.allocations());
            session.allocations().append(allocation);
            return null;
        });
        assertThrows(IllegalStateException.class, () -> escaped.get().findAll());
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.allocations().appendCancellation(new AllocationCancellation(allocation.id(), DATE, "Wrong match", CHIEF, TIME));
            try {
                session.allocations().append(allocation);
            } catch (IllegalStateException expected) {
                // A caught duplicate write must still discard the preceding cancellation.
            }
            return null;
        }));
        assertTrue(new DebtService(transactions).cancellations(VIEWER).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> transactions.execute(session -> {
            session.allocations().appendCancellation(new AllocationCancellation(UUID.randomUUID(), DATE, "Unknown", CHIEF, TIME));
            return null;
        }));
    }
}
