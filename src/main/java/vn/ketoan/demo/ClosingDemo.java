package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.ClosingService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ExpenseCreator;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.value.Money;
import static vn.ketoan.demo.FeatureDemoSupport.DATE;
import static vn.ketoan.demo.FeatureDemoSupport.ensure;
import static vn.ketoan.demo.FeatureDemoSupport.id;
import static vn.ketoan.demo.FeatureDemoSupport.post;

public final class ClosingDemo {
    private ClosingDemo() { }
    public static void run(PrintStream output, UnitOfWork unit, Clock clock) {
        FeatureDemoSupport.seed(unit);
        YearMonth month = YearMonth.from(DATE);
        unit.execute(session -> {
            for (Account account : List.of(
                    Account.posting("911", "Income summary", AccountType.RESULT, BalanceSide.CREDIT, null, AccountTracking.NONE),
                    Account.posting("4212", "Current retained earnings", AccountType.EQUITY, BalanceSide.CREDIT, null, AccountTracking.NONE),
                    Account.posting("331", "Trade payables", AccountType.LIABILITY, BalanceSide.CREDIT, null, AccountTracking.PARTNER))) {
                if (session.accounts().findByCode(account.getCode()).isEmpty()) { session.accounts().insert(account); }
            }
            if (session.partners().findByCode("NCC_CLOSE").isEmpty()) {
                session.partners().insert(new Partner("NCC_CLOSE", "Closing demo supplier", Set.of(PartnerRole.SUPPLIER)));
            }
            return null;
        });
        boolean empty = unit.execute(session -> session.journal().findAll().stream().noneMatch(entry -> month.equals(YearMonth.from(entry.getPostingDate()))));
        if (empty) {
            post(unit, clock, ensure(unit, clock, new RevenueCreator(), new Draft("CLOSE_SAMPLE_REV", DATE, DATE, "Service revenue", List.of(
                    VoucherLine.debit("131", Money.of(10_000_000), "KH_CORR", null), VoucherLine.credit("5113", Money.of(10_000_000), null, null)))));
            post(unit, clock, ensure(unit, clock, new ExpenseCreator(), new Draft("CLOSE_SAMPLE_EXP", DATE, DATE, "Supplier expense", List.of(
                    VoucherLine.debit("6427", Money.of(3_000_000), null, null), VoucherLine.credit("331", Money.of(3_000_000), "NCC_CLOSE", null)))));
        }
        var service = new ClosingService(unit, clock);
        var request = new ClosingRequest(id("demo-closing-2026-11"), "KC_DEMO_202611", month, "911", "4212");
        var preview = service.preview(request, 4);
        output.println("PERSISTENT MONTH-END CLOSING DEMO: " + month);
        output.printf("Ordinary journal basis: revenue=%s; expense=%s; profit/loss=%s%n", preview.revenue(), preview.expense(), preview.profit());
        var batch = service.prepare(request, 1);
        output.println("Reserved source entries=" + batch.sourceEntryIds().size() + "; generated closing vouchers=" + batch.documentIds().size());
        service.approve(batch.id(), 2);
        service.close(batch.id(), 2);
        var ledger = new LedgerService(unit);
        for (String code : List.of("5113", "6427", "911", "4212")) {
            output.println("Account " + code + " debit-minus-credit balance=" + ledger.netBalance(code, month.atEndOfMonth(), 4));
        }
        output.println("Period status=" + unit.execute(session -> session.periods().findByMonth(month).orElseThrow().getStatus()));
        output.println("Closing decisions=" + service.history(batch.id(), 4).size() + "; repeating this command creates no new closing entries.");
        output.println("Cash and outstanding debts carry forward. Corrections to business vouchers use a later open period.");
    }
}
