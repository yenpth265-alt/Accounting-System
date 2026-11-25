package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import vn.ketoan.application.service.ClosingService;
import vn.ketoan.application.service.ReportingService;
import vn.ketoan.cli.ReportPrinter;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ExpenseCreator;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.report.LedgerQuery;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;
import static vn.ketoan.demo.FeatureDemoSupport.DATE;
import static vn.ketoan.demo.FeatureDemoSupport.ensure;
import static vn.ketoan.demo.FeatureDemoSupport.id;
import static vn.ketoan.demo.FeatureDemoSupport.post;

public final class ReportDemo {
    private ReportDemo() { }
    public static void run(PrintStream output) {
        var unit = new InMemoryUnitOfWork();
        Clock clock = Clock.systemUTC();
        FeatureDemoSupport.seed(unit);
        unit.execute(session -> {
            session.accounts().insert(Account.posting("911", "Income summary", AccountType.RESULT, BalanceSide.CREDIT, null, AccountTracking.NONE));
            session.accounts().insert(Account.posting("4212", "Retained earnings", AccountType.EQUITY, BalanceSide.CREDIT, null, AccountTracking.NONE));
            session.accounts().insert(Account.posting("331", "Payables", AccountType.LIABILITY, BalanceSide.CREDIT, null, AccountTracking.PARTNER));
            session.partners().insert(new Partner("NCC_REPORT", "Supplier", Set.of(PartnerRole.SUPPLIER)));
            session.partners().insert(new Partner("KH_ADV", "Customer with an advance", Set.of(PartnerRole.CUSTOMER)));
            return null;
        });
        post(unit, clock, ensure(unit, clock, new RevenueCreator(), new Draft("RPT_SALE", DATE, DATE, "Service delivered", List.of(
                VoucherLine.debit("131", Money.of(10_000_000), "KH_CORR", null), VoucherLine.credit("5113", Money.of(10_000_000), null, null)))));
        post(unit, clock, ensure(unit, clock, new ExpenseCreator(), new Draft("RPT_EXPENSE", DATE, DATE, "Supplier service", List.of(
                VoucherLine.debit("6427", Money.of(3_000_000), null, null), VoucherLine.credit("331", Money.of(3_000_000), "NCC_REPORT", null)))));
        post(unit, clock, ensure(unit, clock, new ReceiptCreator(), new Draft("RPT_RECEIPT", DATE, DATE, "Partial collection", List.of(
                VoucherLine.debit("1111", Money.of(4_000_000), null, "CASH01"), VoucherLine.credit("131", Money.of(4_000_000), "KH_CORR", null)))));
        post(unit, clock, ensure(unit, clock, new ReceiptCreator(), new Draft("RPT_ADVANCE", DATE, DATE, "Another customer's advance", List.of(
                VoucherLine.debit("1111", Money.of(2_000_000), null, "CASH01"), VoucherLine.credit("131", Money.of(2_000_000), "KH_ADV", null)))));
        var reporting = new ReportingService(unit);
        var period = ReportPeriod.month(YearMonth.from(DATE));
        output.println("REPORT DEMO IN MEMORY: no MySQL data is changed.");
        output.println("BEFORE CLOSING");
        ReportPrinter.statements(output, reporting.statements(period, 4));
        var closing = new ClosingService(unit, clock);
        var batch = closing.prepare(new ClosingRequest(id("report-demo-closing"), "RPT_CLOSE", YearMonth.from(DATE), "911", "4212"), 1);
        closing.approve(batch.id(), 2);
        closing.close(batch.id(), 2);
        output.println("AFTER CLOSING: income remains 10000000 - 3000000 = 7000000.");
        ReportPrinter.statements(output, reporting.statements(period, 4));
        ReportPrinter.journal(output, reporting.journal(period, 4));
        ReportPrinter.ledger(output, reporting.ledger(new LedgerQuery(period, "131", "KH_CORR", null), 4));
        ReportPrinter.ledger(output, reporting.ledger(new LedgerQuery(period, "1111", null, "CASH01"), 4));
    }
}
