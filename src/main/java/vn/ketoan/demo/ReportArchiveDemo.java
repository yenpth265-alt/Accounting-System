package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import vn.ketoan.application.service.CashFlowClassificationService;
import vn.ketoan.application.service.ClosingService;
import vn.ketoan.application.service.ReportingService;
import vn.ketoan.application.service.ReportVersionService;
import vn.ketoan.cli.ReportPrinter;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ExpenseCreator;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.PaymentCreator;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.report.CashFlowActivity;
import vn.ketoan.domain.report.CashFlowClassificationRequest;
import vn.ketoan.domain.report.CashFlowDirection;
import vn.ketoan.domain.report.CashFlowSplit;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.SaveReportVersionRequest;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;
import static vn.ketoan.demo.FeatureDemoSupport.DATE;

public final class ReportArchiveDemo {
    private ReportArchiveDemo() { }
    public static void run(PrintStream output) {
        var unit = new InMemoryUnitOfWork();
        Clock clock = Clock.systemUTC();
        FeatureDemoSupport.seed(unit);
        unit.execute(session -> {
            for (var account : List.of(Account.posting("911", "Income summary", AccountType.RESULT, BalanceSide.CREDIT, null, AccountTracking.NONE),
                    Account.posting("4212", "Retained earnings", AccountType.EQUITY, BalanceSide.CREDIT, null, AccountTracking.NONE),
                    Account.posting("331", "Payables", AccountType.LIABILITY, BalanceSide.CREDIT, null, AccountTracking.PARTNER),
                    Account.posting("2111", "Equipment", AccountType.ASSET, BalanceSide.DEBIT, null, AccountTracking.NONE))) { session.accounts().insert(account); }
            session.partners().insert(new Partner("NCC_ARCHIVE", "Supplier", Set.of(PartnerRole.SUPPLIER)));
            return null;
        });
        class Entries {
            JournalEntry post(VoucherCreator creator, String reference, VoucherLine... lines) {
                var draft = new Draft(reference, DATE, DATE, reference, List.of(lines));
                return FeatureDemoSupport.post(unit, clock, FeatureDemoSupport.ensure(unit, clock, creator, draft));
            }
        }
        var entries = new Entries();
        var capital = entries.post(new GeneralCreator(), "ARC_CAPITAL", VoucherLine.debit("1121", Money.of(10_000_000), null, "BANK_PP"), VoucherLine.credit("4111", Money.of(10_000_000), null, null));
        entries.post(new RevenueCreator(), "ARC_SALE", VoucherLine.debit("131", Money.of(10_000_000), "KH_CORR", null), VoucherLine.credit("5113", Money.of(10_000_000), null, null));
        entries.post(new ExpenseCreator(), "ARC_EXPENSE", VoucherLine.debit("6427", Money.of(3_000_000), null, null), VoucherLine.credit("331", Money.of(3_000_000), "NCC_ARCHIVE", null));
        var receipt = entries.post(new ReceiptCreator(), "ARC_RECEIPT", VoucherLine.debit("1111", Money.of(4_000_000), null, "CASH01"), VoucherLine.credit("131", Money.of(4_000_000), "KH_CORR", null));
        var payment = entries.post(new PaymentCreator(), "ARC_PAYMENT", VoucherLine.debit("331", Money.of(2_000_000), "NCC_ARCHIVE", null), VoucherLine.credit("1121", Money.of(2_000_000), null, "BANK_PP"));
        var equipment = entries.post(new PaymentCreator(), "ARC_EQUIPMENT", VoucherLine.debit("2111", Money.of(6_000_000), null, null), VoucherLine.credit("1121", Money.of(6_000_000), null, "BANK_PP"));
        entries.post(new GeneralCreator(), "ARC_TRANSFER", VoucherLine.debit("1121", Money.of(1_000_000), null, "BANK_PP"), VoucherLine.credit("1111", Money.of(1_000_000), null, "CASH01"));
        var period = ReportPeriod.month(YearMonth.from(DATE));
        var versions = new ReportVersionService(unit, clock);
        var draft = versions.save(new SaveReportVersionRequest(UUID.randomUUID(), period, "Draft before classification and closing"), 1);
        var classifications = new CashFlowClassificationService(unit, clock);
        record Purpose(JournalEntry entry, CashFlowActivity activity, CashFlowDirection direction, long amount) { }
        for (var value : List.of(new Purpose(capital, CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, 10_000_000),
                new Purpose(receipt, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000),
                new Purpose(payment, CashFlowActivity.OPERATING, CashFlowDirection.OUTFLOW, 2_000_000),
                new Purpose(equipment, CashFlowActivity.INVESTING, CashFlowDirection.OUTFLOW, 6_000_000))) {
            classifications.classify(new CashFlowClassificationRequest(UUID.randomUUID(), value.entry().getId(), 0, "Cash purpose: " + value.entry().getContent().reference(),
                    Money.ZERO, List.of(new CashFlowSplit(value.activity(), value.direction(), Money.of(value.amount())))), 1);
        }
        var closing = new ClosingService(unit, clock);
        var batch = closing.prepare(new ClosingRequest(UUID.randomUUID(), "ARC_CLOSE", YearMonth.from(DATE), "911", "4212"), 1);
        closing.approve(batch.id(), 2); closing.close(batch.id(), 2);
        var reviewed = versions.save(new SaveReportVersionRequest(UUID.randomUUID(), period, "Classified closed-period report"), 1);
        versions.publish(reviewed.id(), "Reviewed balances, cash movements and explanatory notes", 2);
        var report = new ReportingService(unit).complete(period, 4);
        output.println("REPORT VERSIONS DEMO IN MEMORY");
        ReportPrinter.statements(output, report.financials());
        ReportPrinter.cashFlow(output, report.cashFlow());
        ReportPrinter.notes(output, report.notes());
        output.printf("Stored versions=%d; publications=%d%n", versions.versions(period, 4).size(), versions.publications(4).size());
        output.printf("Version 1 remains frozen: periodsClosed=%s; cashFlowComplete=%s; unchanged=%s%n", draft.periodsClosed(), draft.cashFlowComplete(),
                draft.documentChecksum().equals(versions.get(draft.id(), 4).documentChecksum()));
        output.printf("Version 2 published: tables=%d; fingerprint=%s%n", reviewed.document().tables().size(), reviewed.documentChecksum());
    }
}
