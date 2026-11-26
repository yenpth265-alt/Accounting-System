package vn.ketoan.application.service;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.report.CashFlowActivity;
import vn.ketoan.domain.report.CashFlowClassificationRequest;
import vn.ketoan.domain.report.CashFlowDirection;
import vn.ketoan.domain.report.CashFlowSplit;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;
import static org.junit.jupiter.api.Assertions.*;
import static vn.ketoan.ClosingTestData.general;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.ReportVersionTestData.*;
import static vn.ketoan.ReportingTestData.*;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class CashFlowClassificationServiceTest {
    private InMemoryUnitOfWork unit;
    @BeforeEach void setUp() { unit = transactions(); seedBusiness(unit); }

    @Test void unclassifiedReceiptsAreVisibleAndNoncashRevenueAndExpensesDoNotBecomeCashFlows() {
        var flow = reporting(unit).cashFlow(NOVEMBER, VIEWER);
        assertEquals(Money.ZERO, flow.openingCash());
        assertEquals(Money.of(4_000_000), flow.closingCash());
        assertEquals(Money.of(4_000_000), flow.inflow(CashFlowActivity.UNCLASSIFIED));
        assertEquals(1, flow.rows().size());
        assertFalse(flow.complete());
        classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        var classified = reporting(unit).cashFlow(NOVEMBER, VIEWER);
        assertTrue(classified.complete());
        assertEquals(Money.of(4_000_000), classified.net(CashFlowActivity.OPERATING));
    }

    @Test void pureCashToBankTransfersDoNotInflateExternalInflowsOrOutflows() {
        classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        general(unit, "TRANSFER", VoucherLine.debit("1121", Money.of(2_000_000), null, "BANK01"), VoucherLine.credit("1111", Money.of(2_000_000), null, "CASH01"));
        var flow = reporting(unit).cashFlow(NOVEMBER, VIEWER);
        assertEquals(1, flow.rows().size());
        assertEquals(Money.of(2_000_000), flow.internalTransfers().getFirst().amount());
        assertEquals(Money.of(4_000_000), flow.closingCash());
        assertTrue(flow.complete());
        var notes = reporting(unit).notes(NOVEMBER, VIEWER);
        assertEquals(Money.of(2_000_000), notes.funds().stream().filter(value -> value.code().equals("CASH01")).findFirst().orElseThrow().closing().net());
        assertEquals(Money.of(2_000_000), notes.funds().stream().filter(value -> value.code().equals("BANK01")).findFirst().orElseThrow().closing().net());
    }

    @Test void mixedExternalReceiptsAndPaymentsAreNotSilentlyNettedIntoOneActivity() {
        var mixed = general(unit, "MIXED", VoucherLine.debit("1121", Money.of(10_000_000), null, "BANK01"), VoucherLine.debit("6427", Money.of(2_000_000), null, null),
                VoucherLine.credit("4111", Money.of(10_000_000), null, null), VoucherLine.credit("1111", Money.of(2_000_000), null, "CASH01"));
        assertThrows(IllegalArgumentException.class, () -> classifications(unit).classify(classification(mixed, 0, CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, 8_000_000), OWNER));
        var request = new CashFlowClassificationRequest(UUID.randomUUID(), mixed.getId(), 0, "Capital receipt and separate operating payment", Money.ZERO, List.of(
                new CashFlowSplit(CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, Money.of(10_000_000)),
                new CashFlowSplit(CashFlowActivity.OPERATING, CashFlowDirection.OUTFLOW, Money.of(2_000_000))));
        classifications(unit).classify(request, OWNER);
        var flow = reporting(unit).cashFlow(NOVEMBER, VIEWER);
        assertEquals(Money.of(10_000_000), flow.inflow(CashFlowActivity.FINANCING));
        assertEquals(Money.of(2_000_000), flow.outflow(CashFlowActivity.OPERATING));
        assertEquals(Money.of(12_000_000), flow.closingCash());
    }

    @Test void explicitlyIdentifiedInternalPortionsOfMixedEntriesReconcileBothCashSides() {
        var mixed = general(unit, "MIXED_INTERNAL", VoucherLine.debit("1121", Money.of(5_000_000), null, "BANK01"), VoucherLine.debit("6427", Money.of(1_000_000), null, null),
                VoucherLine.credit("4111", Money.of(3_000_000), null, null), VoucherLine.credit("1111", Money.of(3_000_000), null, "CASH01"));
        var request = new CashFlowClassificationRequest(UUID.randomUUID(), mixed.getId(), 0, "Transfer two, receive three, pay one", Money.of(2_000_000), List.of(
                new CashFlowSplit(CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, Money.of(3_000_000)),
                new CashFlowSplit(CashFlowActivity.OPERATING, CashFlowDirection.OUTFLOW, Money.of(1_000_000))));
        classifications(unit).classify(request, OWNER);
        var flow = reporting(unit).cashFlow(NOVEMBER, VIEWER);
        assertEquals(Money.of(6_000_000), flow.closingCash());
        assertEquals(Money.of(2_000_000), flow.internalTransfers().getFirst().amount());
        assertEquals(Money.of(3_000_000), flow.inflow(CashFlowActivity.FINANCING));
        assertEquals(Money.of(1_000_000), flow.outflow(CashFlowActivity.OPERATING));
    }

    @Test void generatedReversalInheritsTheLatestSourceCategoryWithTheOppositeDirection() {
        var receipt = entry(unit, "REPORT_RECEIPT");
        classifications(unit).classify(classification(receipt, 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        var reversal = vn.ketoan.CorrectionTestData.corrections(unit).createReversal(new ReversalRequest(UUID.randomUUID(), receipt.getSourceDocumentId(),
                "REVERSE_RECEIPT", NOVEMBER.to(), "Cancel receipt"), OWNER);
        var reversed = vn.ketoan.SettlementTestData.postDraft(unit, reversal);
        var flow = reporting(unit).cashFlow(NOVEMBER, VIEWER);
        assertTrue(flow.complete());
        assertEquals(Money.ZERO, flow.net(CashFlowActivity.OPERATING));
        assertEquals(Money.ZERO, flow.closingCash());
        assertTrue(flow.rows().stream().anyMatch(value -> value.inheritedReversal() && value.direction() == CashFlowDirection.OUTFLOW));
        assertThrows(IllegalStateException.class, () -> classifications(unit).classify(classification(reversed, 0, CashFlowActivity.INVESTING, CashFlowDirection.OUTFLOW, 4_000_000), OWNER));
        classifications(unit).classify(classification(receipt, 1, CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        assertEquals(Money.ZERO, reporting(unit).cashFlow(NOVEMBER, VIEWER).net(CashFlowActivity.FINANCING));
        assertEquals(2, reporting(unit).cashFlow(NOVEMBER, VIEWER).rows().stream().filter(value -> value.activity() == CashFlowActivity.FINANCING).count());
    }

    @Test void revisionsAreAppendOnlyIdempotentAndRejectStaleOrChangedRequests() {
        var receipt = entry(unit, "REPORT_RECEIPT");
        var request = classification(receipt, 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000);
        var first = classifications(unit).classify(request, OWNER);
        assertEquals(first, classifications(unit).classify(request, OWNER));
        assertThrows(IllegalStateException.class, () -> classifications(unit).classify(classification(receipt, 0, CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, 4_000_000), OWNER));
        classifications(unit).classify(classification(receipt, 1, CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        assertEquals(first, classifications(unit).classify(request, OWNER));
        assertEquals(2, classifications(unit).history(VIEWER).size());
        assertThrows(IllegalArgumentException.class, () -> classifications(unit).classify(new CashFlowClassificationRequest(request.id(), receipt.getId(), 0,
                "Changed reason", Money.ZERO, request.splits()), OWNER));
    }

    @Test void noncashEntriesWrongTotalsAndUnauthorizedActorsCannotLeaveClassificationsBehind() {
        assertThrows(IllegalArgumentException.class, () -> classifications(unit).classify(new CashFlowClassificationRequest(UUID.randomUUID(), UUID.randomUUID(), 0,
                "Missing source", Money.ZERO, List.of(new CashFlowSplit(CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, Money.of(1)))), OWNER));
        assertThrows(IllegalArgumentException.class, () -> classifications(unit).classify(classification(entry(unit, "REPORT_SALE"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 10_000_000), OWNER));
        assertThrows(IllegalArgumentException.class, () -> classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 3_000_000), OWNER));
        assertThrows(AccessDeniedException.class, () -> classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), VIEWER));
        assertTrue(classifications(unit).history(VIEWER).isEmpty());
    }

    @Test void prepaidAndCorrectionNotesUseHistoricalPostingDatesAndFrozenSchedules() {
        vn.ketoan.CorrectionTestData.seedPrepaid(unit);
        var source = vn.ketoan.CorrectionTestData.prepaidSource(unit, "PREPAID", 6_000_000);
        var service = vn.ketoan.CorrectionTestData.prepaid(unit);
        var plan = service.createPlan(vn.ketoan.CorrectionTestData.plan(source, "PLAN", 3), OWNER);
        vn.ketoan.SettlementTestData.postDraft(unit, service.createInstallment(new vn.ketoan.domain.PrepaidAllocationRequest(UUID.randomUUID(), plan.id(), "NOV_ALLOC", NOVEMBER.to()), OWNER));
        var december = YearMonth.from(NOVEMBER.to()).plusMonths(1);
        vn.ketoan.SettlementTestData.postDraft(unit, service.createInstallment(new vn.ketoan.domain.PrepaidAllocationRequest(UUID.randomUUID(), plan.id(), "DEC_ALLOC", december.atEndOfMonth()), OWNER));
        var note = reporting(unit).notes(NOVEMBER, VIEWER).prepaid().getFirst();
        assertEquals(Money.of(2_000_000), note.recognizedInPeriod());
        assertEquals(Money.of(4_000_000), note.remainingAsset());
        assertEquals(Money.of(2_000_000), reporting(unit).notes(vn.ketoan.domain.report.ReportPeriod.month(december), VIEWER).prepaid().getFirst().recognizedInPeriod());
    }
}
