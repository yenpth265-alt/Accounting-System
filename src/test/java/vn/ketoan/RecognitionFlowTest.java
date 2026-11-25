package vn.ketoan;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.ketoan.application.port.RecognitionUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.RecognitionService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.PaymentCreator;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.approved;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class RecognitionFlowTest {

    @ParameterizedTest
    @CsvSource({"0, 0", "1000000, 300000"})
    void recognizesOnCreditAndSettlesSeparatelyWithoutCountingIncomeOrExpenseTwice(long salesTax, long purchaseTax) {
        UnitOfWork transactions = transactions();
        RecognitionUseCases recognition = new RecognitionService(transactions, validators(), CLOCK);
        LedgerService ledger = new LedgerService(transactions);
        PostingService posting = posting(transactions);
        VoucherDocument sale = recognition.createRevenue(input("DT001", "KH01", "131",
                "5113", 10_000_000, "33311", salesTax), OWNER);
        VoucherDocument cost = recognition.createExpense(input("CP001", "NCC01", "331",
                "6427", 3_000_000, "1331", purchaseTax), OWNER);

        assertTrue(ledger.entries(VIEWER).isEmpty());
        assertEquals(Money.ZERO, ledger.netBalance("131", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.netBalance("331", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.netBalance("5113", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.netBalance("6427", DATE, VIEWER));
        sale = approve(transactions, sale);
        cost = approve(transactions, cost);
        assertTrue(ledger.entries(VIEWER).isEmpty());

        posting.post(sale.getId(), sale.getVersion(), CHIEF);
        posting.post(cost.getId(), cost.getVersion(), CHIEF);
        assertEquals(Money.of(10_000_000 + salesTax), ledger.partnerNetBalance("131", "KH01", DATE, VIEWER));
        assertEquals(Money.of(-3_000_000 - purchaseTax), ledger.partnerNetBalance("331", "NCC01", DATE, VIEWER));
        assertEquals(Money.of(-10_000_000), ledger.netBalance("5113", DATE, VIEWER));
        assertEquals(Money.of(3_000_000), ledger.netBalance("6427", DATE, VIEWER));
        assertEquals(Money.of(-salesTax), ledger.netBalance("33311", DATE, VIEWER));
        assertEquals(Money.of(purchaseTax), ledger.netBalance("1331", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.netBalance("1111", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.netBalance("1121", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.partnerNetBalance("131", "KH02", DATE, VIEWER));
        assertEquals(Money.ZERO, ledger.netBalance("131", DATE.minusDays(1), VIEWER));
        assertEquals(2, ledger.entries(VIEWER).size());

        VoucherDocument receipt = approved(transactions, new ReceiptCreator(), new Draft(
                "PT001", DATE, DATE, "Partial customer collection", List.of(
                        debit("1111", 4_000_000, null, "CASH01"), credit("131", 4_000_000, "KH01", null))));
        VoucherDocument payment = approved(transactions, new PaymentCreator(), new Draft(
                "PC001", DATE, DATE, "Partial supplier settlement", List.of(
                        debit("331", 1_000_000, "NCC01", null), credit("1111", 1_000_000, null, "CASH01"))));
        posting.post(receipt.getId(), receipt.getVersion(), CHIEF);
        posting.post(payment.getId(), payment.getVersion(), CHIEF);
        posting.post(sale.getId(), sale.getVersion(), CHIEF);

        assertEquals(Money.of(6_000_000 + salesTax), ledger.partnerNetBalance("131", "KH01", DATE, VIEWER));
        assertEquals(Money.of(-2_000_000 - purchaseTax), ledger.partnerNetBalance("331", "NCC01", DATE, VIEWER));
        assertEquals(Money.of(3_000_000), ledger.netBalance("1111", DATE, VIEWER));
        assertEquals(Money.of(-10_000_000), ledger.netBalance("5113", DATE, VIEWER));
        assertEquals(Money.of(3_000_000), ledger.netBalance("6427", DATE, VIEWER));
        assertEquals(Money.of(-salesTax), ledger.netBalance("33311", DATE, VIEWER));
        assertEquals(Money.of(purchaseTax), ledger.netBalance("1331", DATE, VIEWER));
        assertEquals(4, ledger.entries(VIEWER).size());
        assertEquals(VoucherStatus.POSTED, transactions.execute(session ->
                session.documents().findById(receipt.getId()).orElseThrow().getStatus()));
    }

    private VoucherDocument approve(UnitOfWork transactions, VoucherDocument draft) {
        return transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument pending = workflow.submit(draft.getId(), draft.getVersion(), OWNER);
            return workflow.approve(pending.getId(), pending.getVersion(), CHIEF);
        });
    }

    private RecognitionDraft input(String reference, String partner, String debtAccount,
                                    String mainAccount, long mainAmount, String taxAccount, long taxAmount) {
        List<RecognitionItem> items = new ArrayList<>();
        items.add(new RecognitionItem(mainAccount, Money.of(mainAmount)));
        if (taxAmount > 0) {
            items.add(new RecognitionItem(taxAccount, Money.of(taxAmount)));
        }
        return new RecognitionDraft(reference, DATE.minusDays(1), DATE, "Service recognized on posting date",
                partner, debtAccount, items);
    }
}
