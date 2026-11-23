package vn.ketoan.application.service;

import org.junit.jupiter.api.Test;

import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.approved;
import static vn.ketoan.PostingTestData.named;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.VoucherTestData.draft;
import static vn.ketoan.VoucherTestData.receipt;
import static vn.ketoan.VoucherTestData.revenue;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.VIEWER;

class LedgerServiceTest {

    @Test
    void recognizesTenMillionReceivableAndFourMillionCollectionOnlyAfterPosting() {
        InMemoryUnitOfWork transactions = transactions();
        LedgerService ledger = new LedgerService(transactions);
        PostingService posting = posting(transactions);
        VoucherDocument sale = approved(transactions, new RevenueCreator(), named("SALE001", revenue()));
        VoucherDocument collection = approved(transactions, new ReceiptCreator(), named("RECEIPT001", receipt()));

        assertEquals(Money.ZERO, ledger.netBalance("131", DATE, VIEWER));
        assertTrue(ledger.entries(VIEWER).isEmpty());
        posting.post(sale.getId(), sale.getVersion(), CHIEF);
        assertEquals(Money.of(10_000_000), ledger.partnerNetBalance("131", "kh01", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.netBalance("1111", DATE, VIEWER));
        posting.post(collection.getId(), collection.getVersion(), CHIEF);
        posting.post(collection.getId(), collection.getVersion(), CHIEF);

        assertEquals(Money.of(6_000_000), ledger.partnerNetBalance("131", "KH01", DATE, VIEWER));
        assertEquals(Money.of(4_000_000), ledger.netBalance("1111", DATE, VIEWER));
        assertEquals(Money.of(-10_000_000), ledger.netBalance("5113", DATE, VIEWER));
        assertEquals(2, ledger.entries(VIEWER).size());
    }

    @Test
    void balancesUsePostingDateAndSeparatePartnerDetails() {
        InMemoryUnitOfWork transactions = transactions();
        LedgerService ledger = new LedgerService(transactions);
        PostingService posting = posting(transactions);
        VoucherDocument a = approved(transactions, new RevenueCreator(), named("SALE_A", revenue()));
        VoucherDocument b = approved(transactions, new RevenueCreator(), named("SALE_B", draft(
                debit("131", 5_000_000, "KH02", null), credit("5113", 5_000_000, null, null))));
        posting.post(a.getId(), a.getVersion(), CHIEF);
        posting.post(b.getId(), b.getVersion(), CHIEF);

        assertEquals(Money.ZERO, ledger.netBalance("131", DATE.minusDays(1), VIEWER));
        assertEquals(Money.of(15_000_000), ledger.netBalance("131", DATE, VIEWER));
        assertEquals(Money.of(10_000_000), ledger.partnerNetBalance("131", "KH01", DATE, VIEWER));
        assertEquals(Money.of(5_000_000), ledger.partnerNetBalance("131", "KH02", DATE, VIEWER));
    }

    @Test
    void historicalBalancesRemainVisibleAfterCatalogDeactivation() {
        InMemoryUnitOfWork transactions = transactions();
        VoucherDocument source = approved(transactions, new ReceiptCreator(), receipt());
        posting(transactions).post(source.getId(), source.getVersion(), CHIEF);
        transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("1111").orElseThrow().deactivate());
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().deactivate());
            return null;
        });
        LedgerService ledger = new LedgerService(transactions);
        assertEquals(Money.of(4_000_000), ledger.netBalance("1111", DATE, VIEWER));
        assertEquals(Money.of(-4_000_000), ledger.partnerNetBalance("131", "KH01", DATE, VIEWER));
    }

    @Test
    void readsEnforcePermissionsAndRejectUnsupportedOrMissingDetails() {
        LedgerService ledger = new LedgerService(transactions());
        assertThrows(AccessDeniedException.class, () -> ledger.entries(ADMIN));
        assertThrows(IllegalArgumentException.class, () -> ledger.netBalance("999", DATE, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> ledger.netBalance("111", DATE, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> ledger.partnerNetBalance("1111", "KH01", DATE, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> ledger.partnerNetBalance("131", "UNKNOWN", DATE, VIEWER));
        assertThrows(UnsupportedOperationException.class, ledger.entries(VIEWER)::clear);
    }
}
