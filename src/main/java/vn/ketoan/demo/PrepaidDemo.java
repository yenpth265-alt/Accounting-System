package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.CorrectionService;
import vn.ketoan.application.service.PrepaidExpenseService;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.PaymentCreator;
import vn.ketoan.domain.PrepaidAllocationRequest;
import vn.ketoan.domain.PrepaidPlanRequest;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.StraightLineAllocation;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;

import static vn.ketoan.demo.FeatureDemoSupport.DATE;
import static vn.ketoan.demo.FeatureDemoSupport.VALIDATORS;
import static vn.ketoan.demo.FeatureDemoSupport.ensure;
import static vn.ketoan.demo.FeatureDemoSupport.id;
import static vn.ketoan.demo.FeatureDemoSupport.key;
import static vn.ketoan.demo.FeatureDemoSupport.post;

public final class PrepaidDemo {

    private PrepaidDemo() { }

    public static void run(PrintStream output, UnitOfWork transactions, Clock clock) {
        FeatureDemoSupport.seed(transactions);
        var service = new PrepaidExpenseService(transactions, VALIDATORS, clock, new StraightLineAllocation());
        YearMonth first = YearMonth.from(DATE);
        output.println("PERSISTENT PREPAID EXPENSE DEMO");
        boolean completed = transactions.execute(session -> session.prepaidAllocations().findById(id("demo-prepaid-jan"))
                .map(value -> session.documents().findById(value.targetDocumentId()).orElseThrow().getStatus() == VoucherStatus.POSTED).orElse(false));
        if (!completed) {
            post(transactions, clock, ensure(transactions, clock, new GeneralCreator(), new Draft("PP_DEMO_FUNDING", DATE, DATE,
                    "Demo bank funding", List.of(VoucherLine.debit("1121", Money.of(6_000_000), null, "BANK_PP"),
                    VoucherLine.credit("4111", Money.of(6_000_000), null, null)))));
            var purchase = post(transactions, clock, ensure(transactions, clock, new PaymentCreator(), new Draft("PP_DEMO_PURCHASE", DATE, DATE,
                    "Pay for a service used over three months", List.of(VoucherLine.debit("242", Money.of(6_000_000), null, null),
                    VoucherLine.credit("1121", Money.of(6_000_000), null, "BANK_PP")))));
            var plan = service.createPlan(new PrepaidPlanRequest(id("demo-prepaid-plan"), "PP_DEMO_PLAN", key(purchase, "242"), "6427", first, 3), 1);
            output.println("PURCHASE: prepaid asset=6000000; immediate expense=0. Schedule=2000000 per month.");
            post(transactions, clock, service.createInstallment(new PrepaidAllocationRequest(id("demo-prepaid-nov"), plan.id(),
                    "PP_DEMO_NOV", first.atEndOfMonth()), 1));
            var december = service.createInstallment(new PrepaidAllocationRequest(id("demo-prepaid-dec"), plan.id(),
                    "PP_DEMO_DEC", first.plusMonths(1).atEndOfMonth()), 1);
            post(transactions, clock, december);
            post(transactions, clock, new CorrectionService(transactions, VALIDATORS, clock).createReversal(new ReversalRequest(
                    id("demo-prepaid-dec-reversal"), december.getId(), "PP_DEMO_REV_DEC", first.plusMonths(1).atEndOfMonth(),
                    "Correct the December installment"), 1));
            output.println("AFTER DECEMBER REVERSAL: recognized=" + service.statement(plan.id(), first.plusMonths(1).atEndOfMonth(), 4).recognized());
            post(transactions, clock, service.createInstallment(new PrepaidAllocationRequest(id("demo-prepaid-dec-redo"), plan.id(),
                    "PP_DEMO_REDO_DEC", first.plusMonths(1).atEndOfMonth()), 1));
            post(transactions, clock, service.createInstallment(new PrepaidAllocationRequest(id("demo-prepaid-jan"), plan.id(),
                    "PP_DEMO_JAN", first.plusMonths(2).atEndOfMonth()), 1));
        } else {
            output.println("Existing completed demo reused; no new postings were created.");
        }
        var statement = service.statement(id("demo-prepaid-plan"), first.plusMonths(2).atEndOfMonth(), 4);
        statement.schedule().forEach(row -> output.printf("%s: planned=%s; recognized=%s%n", row.month(), row.planned(), row.recognized()));
        output.printf("TOTAL: original=%s; recognized=%s; remaining prepaid asset=%s%n",
                statement.original(), statement.recognized(), statement.remainingAsset());
        output.println("The schedule, installments and reversal are stored in SQL; repeating the command reuses them.");
    }
}
