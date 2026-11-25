package vn.ketoan.application.service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.application.port.AllocationUseCases;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.DebtStatement;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLineKey;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.SettlementTestData.allocations;
import static vn.ketoan.SettlementTestData.key;
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.SettlementTestData.receiptInput;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.SettlementTestData.settlements;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.account;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;

class DebtAllocationServiceTest {

    private InMemoryUnitOfWork transactions;
    private AllocationUseCases allocations;
    private DebtService debts;
    private JournalEntry charge;
    private JournalEntry settlement;

    @BeforeEach
    void setUp() {
        transactions = transactions();
        allocations = allocations(transactions);
        debts = new DebtService(transactions);
        charge = sale(transactions, "DT001", 10_000_000);
        settlement = receipt(transactions, "PT001", 4_000_000);
    }

    @Test
    void matchingChangesInvoiceOutstandingButKeepsNetDebtJournalAndSourceAuditUnchanged() {
        LedgerService ledger = new LedgerService(transactions);
        List<JournalEntry> originalJournal = ledger.entries(VIEWER);
        VoucherDocument source = transactions.execute(session ->
                session.documents().findById(settlement.getSourceDocumentId()).orElseThrow());
        DebtStatement before = statement();
        assertEquals(Money.of(10_000_000), before.unsettledDebt());
        assertEquals(Money.of(4_000_000), before.unallocatedSettlements());
        assertEquals(Money.of(6_000_000), before.netDebt());

        DebtAllocation allocation = allocations.allocate(request(charge, settlement, 4_000_000), CHIEF);
        DebtStatement after = statement();
        assertEquals(Money.of(6_000_000), after.unsettledDebt());
        assertEquals(Money.ZERO, after.unallocatedSettlements());
        assertEquals(before.netDebt(), after.netDebt());
        assertEquals(1, after.openItems().size());
        assertEquals(CHIEF, allocation.recordedBy());
        assertEquals(TIME, allocation.recordedAt());
        assertEquals(originalJournal, ledger.entries(VIEWER));
        assertEquals(source, transactions.execute(session ->
                session.documents().findById(settlement.getSourceDocumentId()).orElseThrow()));
        assertEquals(after.netDebt(), ledger.partnerNetBalance("131", "KH01", DATE, VIEWER));
    }

    @Test
    void preventsOverspendingEitherTheSettlementOrTheDebt() {
        allocations.allocate(request(charge, settlement, 4_000_000), CHIEF);
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(request(charge, settlement, 1), CHIEF));
        JournalEntry largeReceipt = receipt(transactions, "PT002", 12_000_000);
        assertThrows(IllegalArgumentException.class,
                () -> allocations.allocate(request(charge, largeReceipt, 6_000_001), CHIEF));
        assertEquals(1, debts.allocations(VIEWER).size());
        assertEquals(Money.of(6_000_000), statement().unsettledDebt());
    }

    @Test
    void supportsManyInvoicesPerReceiptAndManyReceiptsPerInvoice() {
        JournalEntry secondCharge = sale(transactions, "DT002", 5_000_000);
        JournalEntry secondReceipt = receipt(transactions, "PT002", 11_000_000);
        allocations.allocate(request(charge, settlement, 4_000_000), CHIEF);
        allocations.allocate(request(charge, secondReceipt, 6_000_000), CHIEF);
        allocations.allocate(request(secondCharge, secondReceipt, 5_000_000), CHIEF);

        assertEquals(Money.ZERO, statement().unsettledDebt());
        assertEquals(Money.ZERO, statement().unallocatedSettlements());
        assertTrue(statement().openItems().isEmpty());
        assertEquals(3, debts.allocations(VIEWER).size());
    }

    @Test
    void rejectsDifferentPartnersAndDifferentDebtAccounts() {
        RecognitionService recognition = new RecognitionService(transactions, validators(), CLOCK);
        JournalEntry otherPartner = postDraft(transactions, recognition.createRevenue(new RecognitionDraft(
                "DT002", DATE, DATE, "Other customer", "KH02", "131",
                List.of(new RecognitionItem("5113", Money.of(5_000_000)))), OWNER));
        assertThrows(IllegalArgumentException.class,
                () -> allocations.allocate(request(otherPartner, settlement, 1), CHIEF));
        transactions.execute(session -> {
            session.accounts().insert(account("138", AccountType.ASSET, AccountTracking.PARTNER));
            return null;
        });
        JournalEntry otherAccount = postDraft(transactions, recognition.createRevenue(new RecognitionDraft(
                "DT003", DATE, DATE, "Other receivable", "KH01", "138",
                List.of(new RecognitionItem("5113", Money.of(5_000_000)))), OWNER));
        AllocationRequest wrongAccount = new AllocationRequest(UUID.randomUUID(), key(otherAccount, "138"),
                key(settlement, "131"), Money.of(1), DATE, "Wrong account");
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(wrongAccount, CHIEF));
        assertTrue(debts.allocations(VIEWER).isEmpty());
    }

    @Test
    void requiresOpposingPostedDebtLinesAndValidSourceDates() {
        assertThrows(IllegalArgumentException.class,
                () -> allocations.allocate(request(settlement, charge, 1), CHIEF));
        VoucherDocument draft = settlements(transactions).createCustomerReceipt(receiptInput("PT002", 1), OWNER);
        AllocationRequest unposted = new AllocationRequest(UUID.randomUUID(), key(charge, "131"),
                new JournalLineKey(draft.getId(), 2), Money.of(1), DATE, "Unposted source");
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(unposted, CHIEF));
        AllocationRequest moneyLine = new AllocationRequest(UUID.randomUUID(), key(charge, "131"),
                key(settlement, "1111"), Money.of(1), DATE, "Not a debt line");
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(moneyLine, CHIEF));
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(
                request(charge, settlement, "131", 1, DATE.minusDays(1)), CHIEF));
        assertTrue(debts.allocations(VIEWER).isEmpty());
    }

    @Test
    void preventsBackdatedAllocationFromExceedingCapacityAtALaterDate() {
        allocations.allocate(request(charge, settlement, "131", 3_000_000, DATE.plusDays(2)), CHIEF);
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(
                request(charge, settlement, "131", 2_000_000, DATE.plusDays(1)), CHIEF));
        assertEquals(Money.of(4_000_000), debts.statement("131", "KH01", DATE.plusDays(1), VIEWER).unallocatedSettlements());
        assertEquals(Money.of(1_000_000), debts.statement("131", "KH01", DATE.plusDays(2), VIEWER).unallocatedSettlements());
        assertEquals(1, debts.allocations(VIEWER).size());
    }

    @Test
    void retryIsIdempotentAndReusedIdsCannotChangeAmounts() {
        AllocationRequest request = request(charge, settlement, 2_000_000);
        DebtAllocation original = allocations.allocate(request, CHIEF);
        assertEquals(original, allocations.allocate(request, CHIEF));
        AllocationRequest changed = new AllocationRequest(request.id(), request.charge(), request.settlement(),
                Money.of(1_000_000), request.effectiveDate(), request.note());
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(changed, CHIEF));
        assertEquals(1, debts.allocations(VIEWER).size());
        assertEquals(Money.of(8_000_000), statement().unsettledDebt());
    }

    @Test
    void closedPeriodsBlockNewAllocationsAndCancellationsButAllowCompletedRetries() {
        AllocationRequest request = request(charge, settlement, 2_000_000);
        DebtAllocation original = allocations.allocate(request, CHIEF);
        AllocationCancellation cancellation = allocations.cancel(original.id(), DATE, "Wrong invoice", CHIEF);
        DebtAllocation stillActive = allocations.allocate(request(charge, settlement, 1_000_000), CHIEF);
        transactions.execute(session -> {
            session.periods().update(session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow()
                    .beginClosing().completeClosing());
            return null;
        });
        assertEquals(original, allocations.allocate(request, CHIEF));
        assertEquals(cancellation, allocations.cancel(original.id(), DATE, "Wrong invoice", CHIEF));
        assertThrows(IllegalStateException.class,
                () -> allocations.allocate(request(charge, settlement, 1), CHIEF));
        assertThrows(IllegalStateException.class,
                () -> allocations.cancel(stillActive.id(), DATE, "Period already closed", CHIEF));
        assertThrows(IllegalArgumentException.class, () -> allocations.allocate(
                request(charge, settlement, "131", 1, DATE.plusMonths(1)), CHIEF));
        assertEquals(Money.of(9_000_000), statement().unsettledDebt());
    }

    @ParameterizedTest
    @ValueSource(longs = {OWNER, OTHER, VIEWER, ADMIN, 999})
    void permissionsProtectNewRequestsRetriesAndCancellations(long actorId) {
        AllocationRequest request = request(charge, settlement, 2_000_000);
        allocations.allocate(request, CHIEF);
        assertThrows(AccessDeniedException.class, () -> allocations.allocate(request, actorId));
        assertThrows(AccessDeniedException.class, () -> allocations.cancel(request.id(), DATE, "Wrong match", actorId));
        assertEquals(1, debts.allocations(VIEWER).size());
        assertTrue(debts.cancellations(VIEWER).isEmpty());
    }

    @Test
    void inactiveChiefCannotMutateOrRetryAllocations() {
        AllocationRequest request = request(charge, settlement, 2_000_000);
        allocations.allocate(request, CHIEF);
        transactions.execute(session -> {
            session.users().update(session.users().findById(CHIEF).orElseThrow().deactivate());
            return null;
        });
        assertThrows(AccessDeniedException.class, () -> allocations.allocate(request, CHIEF));
        assertThrows(AccessDeniedException.class, () -> allocations.cancel(request.id(), DATE, "Wrong match", CHIEF));
    }

    @Test
    void cancellationPreservesHistoryAndHistoricalStatementsAndNeverChangesLedger() {
        AllocationRequest request = request(charge, settlement, 4_000_000);
        DebtAllocation original = allocations.allocate(request, CHIEF);
        List<JournalEntry> journal = new LedgerService(transactions).entries(VIEWER);
        AllocationCancellation cancellation = allocations.cancel(request.id(), DATE.plusDays(1), "  Wrong invoice  ", CHIEF);

        DebtStatement historical = debts.statement("131", "KH01", DATE, VIEWER);
        DebtStatement current = debts.statement("131", "KH01", DATE.plusDays(1), VIEWER);
        assertEquals(Money.of(6_000_000), historical.unsettledDebt());
        assertEquals(Money.ZERO, historical.unallocatedSettlements());
        assertEquals(Money.of(10_000_000), current.unsettledDebt());
        assertEquals(Money.of(4_000_000), current.unallocatedSettlements());
        assertEquals(historical.netDebt(), current.netDebt());
        assertEquals(List.of(original), debts.allocations(VIEWER));
        assertEquals(List.of(cancellation), debts.cancellations(VIEWER));
        assertEquals("Wrong invoice", cancellation.reason());
        assertEquals(cancellation, allocations.cancel(request.id(), DATE.plusDays(1), "Wrong invoice", CHIEF));
        assertThrows(IllegalArgumentException.class,
                () -> allocations.cancel(request.id(), DATE.plusDays(2), "Other date", CHIEF));
        assertEquals(journal, new LedgerService(transactions).entries(VIEWER));
        assertEquals(original, allocations.allocate(request, CHIEF));
    }

    @Test
    void cancelledSettlementCanBeMatchedToAnotherInvoice() {
        AllocationRequest first = request(charge, settlement, 4_000_000);
        allocations.allocate(first, CHIEF);
        allocations.cancel(first.id(), DATE.plusDays(1), "Choose another invoice", CHIEF);
        JournalEntry other = sale(transactions, "DT002", 5_000_000);
        allocations.allocate(request(other, settlement, "131", 4_000_000, DATE.plusDays(1)), CHIEF);
        DebtStatement current = debts.statement("131", "KH01", DATE.plusDays(1), VIEWER);
        assertEquals(Money.of(10_000_000), current.items().stream().filter(item -> item.reference().equals("DT001"))
                .findFirst().orElseThrow().remaining());
        assertEquals(Money.of(1_000_000), current.items().stream().filter(item -> item.reference().equals("DT002"))
                .findFirst().orElseThrow().remaining());
        assertEquals(Money.ZERO, current.unallocatedSettlements());
    }

    @Test
    void rejectsCancellationBeforeAllocationAndEmptyReasonsOrMissingRecords() {
        AllocationRequest request = request(charge, settlement, 2_000_000);
        allocations.allocate(request, CHIEF);
        assertThrows(IllegalArgumentException.class,
                () -> allocations.cancel(request.id(), DATE.minusDays(1), "Too early", CHIEF));
        assertThrows(IllegalArgumentException.class, () -> allocations.cancel(request.id(), DATE, " ", CHIEF));
        assertThrows(IllegalArgumentException.class, () -> allocations.cancel(UUID.randomUUID(), DATE, "Unknown", CHIEF));
        DebtAllocationService pastClock = new DebtAllocationService(transactions,
                Clock.fixed(TIME.minusSeconds(1), ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> pastClock.cancel(request.id(), DATE, "Earlier timestamp", CHIEF));
        assertThrows(IllegalArgumentException.class, () -> pastClock.allocate(request(charge, settlement, 1), CHIEF));
        assertTrue(debts.cancellations(VIEWER).isEmpty());
        assertEquals(1, debts.allocations(VIEWER).size());
    }

    @Test
    void concurrentAllocationsCannotBothSpendTheSameAvailableAmount() throws Exception {
        Callable<Boolean> task = () -> {
            try {
                allocations.allocate(request(charge, settlement, 3_000_000), CHIEF);
                return true;
            } catch (IllegalArgumentException expected) {
                return false;
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(task);
            Future<Boolean> second = executor.submit(task);
            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        }
        assertEquals(1, debts.allocations(VIEWER).size());
        assertEquals(Money.of(1_000_000), statement().unallocatedSettlements());
    }

    private DebtStatement statement() {
        return debts.statement("131", "KH01", DATE, VIEWER);
    }
}
