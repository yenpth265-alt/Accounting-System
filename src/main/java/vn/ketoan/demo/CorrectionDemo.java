package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.util.List;

import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.CorrectionService;
import vn.ketoan.application.service.DebtAllocationService;
import vn.ketoan.application.service.DebtService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.domain.AdjustmentRequest;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.value.Money;

import static vn.ketoan.demo.FeatureDemoSupport.DATE;
import static vn.ketoan.demo.FeatureDemoSupport.VALIDATORS;
import static vn.ketoan.demo.FeatureDemoSupport.approve;
import static vn.ketoan.demo.FeatureDemoSupport.ensure;
import static vn.ketoan.demo.FeatureDemoSupport.id;
import static vn.ketoan.demo.FeatureDemoSupport.key;
import static vn.ketoan.demo.FeatureDemoSupport.post;

public final class CorrectionDemo {

    private CorrectionDemo() { }

    public static void run(PrintStream output, UnitOfWork transactions, Clock clock) {
        FeatureDemoSupport.seed(transactions);
        output.println("PERSISTENT CORRECTION DEMO");
        boolean completed = transactions.execute(session -> session.corrections().findById(id("demo-source-reversal"))
                .map(value -> session.documents().findById(value.targetDocumentId()).orElseThrow().getStatus() == VoucherStatus.POSTED).orElse(false));
        if (!completed) {
            var source = post(transactions, clock, ensure(transactions, clock, new RevenueCreator(), new Draft(
                    "CORR_DEMO_SALE", DATE, DATE, "Service later cancelled", List.of(
                    VoucherLine.debit("131", Money.of(10_000_000), "KH_CORR", null),
                    VoucherLine.credit("5113", Money.of(10_000_000), null, null)))));
            var corrections = new CorrectionService(transactions, VALIDATORS, clock);
            var delta = corrections.createAdjustment(new AdjustmentRequest(id("demo-service-delta"), source.getSourceDocumentId(),
                    "Increase service value by two million", new Draft("CORR_DEMO_DELTA", DATE, DATE, "Service value delta", List.of(
                    VoucherLine.debit("131", Money.of(2_000_000), "KH_CORR", null),
                    VoucherLine.credit("5113", Money.of(2_000_000), null, null)))), 1);
            post(transactions, clock, delta);
            output.println("ADJUSTMENT: original 10000000 + delta 2000000; source voucher stays unchanged.");
            post(transactions, clock, corrections.createReversal(new ReversalRequest(id("demo-delta-reversal"), delta.getId(),
                    "CORR_DEMO_REV_DELTA", DATE, "Undo the delta before voiding its source"), 1));
            var receipt = post(transactions, clock, ensure(transactions, clock, new ReceiptCreator(), new Draft(
                    "CORR_DEMO_RECEIPT", DATE, DATE, "Customer paid part of the service", List.of(
                    VoucherLine.debit("1111", Money.of(4_000_000), null, "CASH01"),
                    VoucherLine.credit("131", Money.of(4_000_000), "KH_CORR", null)))));
            var allocationService = new DebtAllocationService(transactions, clock);
            var allocation = allocationService.allocate(new AllocationRequest(id("demo-correction-payment"), key(source, "131"),
                    key(receipt, "131"), Money.of(4_000_000), DATE, "Match the collection to the service"), 2);
            var reversal = approve(transactions, clock, corrections.createReversal(new ReversalRequest(id("demo-source-reversal"),
                    source.getSourceDocumentId(), "CORR_DEMO_REV_SOURCE", DATE, "Void the cancelled service"), 1));
            if (reversal.getStatus() != VoucherStatus.POSTED) {
                try {
                    post(transactions, clock, reversal);
                } catch (IllegalStateException expected) {
                    output.println("BLOCKED while the invoice is allocated: " + expected.getMessage());
                }
                allocationService.cancel(allocation.id(), DATE, "Unmatch before the full reversal", 2);
                post(transactions, clock, reversal);
            }
        } else {
            output.println("Existing completed demo reused; no new postings were created.");
        }
        var statement = new DebtService(transactions).statement("131", "KH_CORR", DATE, 4);
        var entries = new LedgerService(transactions).entries(4).stream()
                .filter(value -> value.getContent().reference().startsWith("CORR_DEMO_")).toList();
        Money revenueEffect = entries.stream().flatMap(value -> value.getLines().stream())
                .filter(line -> line.getAccountCode().equals("5113"))
                .map(line -> line.getCredit().subtract(line.getDebit())).reduce(Money.ZERO, Money::add);
        output.printf("RESULT: demo revenue=%s; customer net receivable=%s; open invoices=%s; unallocated collections=%s%n",
                revenueEffect, statement.netDebt(), statement.unsettledDebt(), statement.unallocatedSettlements());
        output.println("The collected 4000000 remains an advance/refundable amount. Original documents and all correction links are retained.");
        output.println("Demo journal entries=" + entries.size() + "; repeat this command to read the same SQL data.");
    }
}
