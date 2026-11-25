package vn.ketoan;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.AllocationUseCases;
import vn.ketoan.application.port.SettlementUseCases;
import vn.ketoan.application.service.DebtService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.RecognitionService;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.DebtStatement;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.SettlementDraft;
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
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.SettlementTestData.receiptInput;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.SettlementTestData.settlements;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class SettlementFlowTest {

    @Test
    void partialCollectionsAndPaymentsSettleTaxInclusiveDebtsWithoutRecognizingIncomeAgain() {
        InMemoryUnitOfWork transactions = transactions();
        RecognitionService recognition = new RecognitionService(transactions, validators(), CLOCK);
        SettlementUseCases settlements = settlements(transactions);
        AllocationUseCases allocations = allocations(transactions);
        DebtService debts = new DebtService(transactions);
        LedgerService ledger = new LedgerService(transactions);
        JournalEntry sale = postDraft(transactions, recognition.createRevenue(new RecognitionDraft(
                "DT001", DATE, DATE, "Service with tax", "KH01", "131", List.of(
                        new RecognitionItem("5113", Money.of(10_000_000)),
                        new RecognitionItem("33311", Money.of(1_000_000)))), OWNER));
        JournalEntry expense = postDraft(transactions, recognition.createExpense(new RecognitionDraft(
                "CP001", DATE, DATE, "Service with recoverable tax", "NCC01", "331", List.of(
                        new RecognitionItem("6427", Money.of(3_000_000)),
                        new RecognitionItem("1331", Money.of(300_000)))), OWNER));
        VoucherDocument receiptDraft = settlements.createCustomerReceipt(receiptInput("PT001", 4_000_000), OWNER);
        VoucherDocument paymentDraft = settlements.createSupplierPayment(new SettlementDraft(
                "PC001", DATE, DATE, "Pay supplier", "NCC01", "331", "CASH01", Money.of(1_000_000)), OWNER);
        assertEquals(Money.ZERO, ledger.netBalance("1111", DATE, VIEWER));
        assertEquals(1, debts.statement("131", "KH01", DATE, VIEWER).items().size());

        JournalEntry receipt = postDraft(transactions, receiptDraft);
        JournalEntry payment = postDraft(transactions, paymentDraft);
        allocations.allocate(request(sale, receipt, 4_000_000), CHIEF);
        allocations.allocate(request(expense, payment, "331", 1_000_000, DATE), CHIEF);
        DebtStatement receivables = debts.statement("131", "KH01", DATE, VIEWER);
        DebtStatement payables = debts.statement("331", "NCC01", DATE, VIEWER);

        assertEquals(Money.of(7_000_000), receivables.unsettledDebt());
        assertEquals(Money.of(2_300_000), payables.unsettledDebt());
        assertEquals(Money.ZERO, receivables.unallocatedSettlements());
        assertEquals(Money.ZERO, payables.unallocatedSettlements());
        assertEquals(receivables.netDebt(), ledger.partnerNetBalance("131", "KH01", DATE, VIEWER));
        assertEquals(payables.netDebt().negate(), ledger.partnerNetBalance("331", "NCC01", DATE, VIEWER));
        assertEquals(Money.of(3_000_000), ledger.netBalance("1111", DATE, VIEWER));
        assertEquals(Money.of(-10_000_000), ledger.netBalance("5113", DATE, VIEWER));
        assertEquals(Money.of(3_000_000), ledger.netBalance("6427", DATE, VIEWER));
        assertEquals(Money.of(-1_000_000), ledger.netBalance("33311", DATE, VIEWER));
        assertEquals(Money.of(300_000), ledger.netBalance("1331", DATE, VIEWER));
        assertEquals(4, ledger.entries(VIEWER).size());
    }

    @Test
    void advancesAndExcessCollectionsRemainAvailableForLaterInvoices() {
        InMemoryUnitOfWork transactions = transactions();
        DebtService debts = new DebtService(transactions);
        AllocationUseCases allocations = allocations(transactions);
        JournalEntry advance = receipt(transactions, "ADVANCE", 12_000_000);
        DebtStatement beforeSale = debts.statement("131", "KH01", DATE, VIEWER);
        assertEquals(Money.ZERO, beforeSale.unsettledDebt());
        assertEquals(Money.of(12_000_000), beforeSale.unallocatedSettlements());
        assertEquals(Money.of(-12_000_000), beforeSale.netDebt());
        assertEquals(Money.ZERO, new LedgerService(transactions).netBalance("5113", DATE, VIEWER));

        JournalEntry firstSale = sale(transactions, "DT001", 10_000_000);
        allocations.allocate(request(firstSale, advance, 10_000_000), CHIEF);
        DebtStatement excess = debts.statement("131", "KH01", DATE, VIEWER);
        assertEquals(Money.ZERO, excess.unsettledDebt());
        assertEquals(Money.of(2_000_000), excess.unallocatedSettlements());
        assertEquals(Money.of(-2_000_000), excess.netDebt());
        JournalEntry secondSale = sale(transactions, "DT002", 5_000_000);
        allocations.allocate(request(secondSale, advance, 2_000_000), CHIEF);
        DebtStatement remaining = debts.statement("131", "KH01", DATE, VIEWER);
        assertEquals(Money.of(3_000_000), remaining.unsettledDebt());
        assertEquals(Money.ZERO, remaining.unallocatedSettlements());
        assertEquals(remaining.netDebt(), new LedgerService(transactions).partnerNetBalance("131", "KH01", DATE, VIEWER));
    }

    @Test
    void historicalQueriesEnforcePermissionsAndKeepInactiveCatalogHistoryReadable() {
        InMemoryUnitOfWork transactions = transactions();
        JournalEntry sale = sale(transactions, "DT001", 10);
        JournalEntry receipt = receipt(transactions, "PT001", 4);
        allocations(transactions).allocate(request(sale, receipt, 4), CHIEF);
        transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("131").orElseThrow().deactivate());
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().deactivate());
            return null;
        });
        DebtService debts = new DebtService(transactions);
        assertEquals(Money.of(6), debts.statement("131", "kh01", DATE, VIEWER).netDebt());
        assertTrue(debts.statement("131", "KH01", DATE.minusDays(1), VIEWER).items().isEmpty());
        assertThrows(AccessDeniedException.class, () -> debts.statement("131", "KH01", DATE, ADMIN));
        assertThrows(AccessDeniedException.class, () -> debts.allocations(ADMIN));
        assertThrows(AccessDeniedException.class, () -> debts.cancellations(ADMIN));
        assertThrows(IllegalArgumentException.class, () -> debts.statement("1111", "KH01", DATE, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> debts.statement("131", "UNKNOWN", DATE, VIEWER));
        assertThrows(UnsupportedOperationException.class, debts.statement("131", "KH01", DATE, VIEWER).items()::clear);
        assertThrows(UnsupportedOperationException.class, debts.allocations(VIEWER)::clear);
    }
}
