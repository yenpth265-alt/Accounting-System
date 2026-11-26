package vn.ketoan.cli;

import java.io.PrintStream;
import vn.ketoan.domain.report.AccountLedger;
import vn.ketoan.domain.report.DebitCredit;
import vn.ketoan.domain.report.FinancialStatements;
import vn.ketoan.domain.report.GeneralJournal;
import vn.ketoan.domain.report.CashFlowActivity;
import vn.ketoan.domain.report.CashFlowStatement;
import vn.ketoan.domain.report.ReportNotes;
import vn.ketoan.domain.report.ReportDocument;

/** Console presentation only. Calculations and storage stay outside this class. */
public final class ReportPrinter {
    private ReportPrinter() { }
    public static void cashFlow(PrintStream output, CashFlowStatement statement) {
        output.printf("CASH FLOW: opening=%s; closing=%s; fully classified=%s%n", statement.openingCash(), statement.closingCash(), statement.complete());
        for (var activity : CashFlowActivity.values()) {
            output.printf("%s: inflow=%s; outflow=%s; net=%s%n", activity, statement.inflow(activity), statement.outflow(activity), statement.net(activity));
        }
        statement.rows().forEach(row -> output.printf("%s %s %s %s %s%s%n", row.postingDate(), row.reference(), row.activity(), row.direction(), row.amount(), row.inheritedReversal() ? " [reversal]" : ""));
        statement.internalTransfers().forEach(row -> output.printf("INTERNAL TRANSFER %s: %s (excluded from external flows)%n", row.reference(), row.amount()));
    }
    public static void notes(PrintStream output, ReportNotes notes) {
        output.println("REPORT NOTES");
        notes.disclosures().forEach(output::println);
        notes.funds().forEach(row -> output.printf("FUND %s: opening=%s; in=%s; out=%s; closing=%s%n", row.code(), row.opening().net(), row.movements().debit(), row.movements().credit(), row.closing().net()));
        notes.partners().forEach(row -> output.printf("PARTNER %s/%s: closing Dr=%s Cr=%s%n", row.accountCode(), row.partnerCode(), row.closing().debit(), row.closing().credit()));
        notes.prepaid().forEach(row -> output.printf("PREPAID %s: recognized this period=%s; remaining=%s%n", row.reference(), row.recognizedInPeriod(), row.remainingAsset()));
        notes.corrections().forEach(row -> output.printf("CORRECTION %s -> %s: %s; %s%n", row.sourceReference(), row.targetReference(), row.kind(), row.reason()));
    }
    public static void document(PrintStream output, ReportDocument document) {
        output.printf("FROZEN REPORT %s to %s%n", document.period().from(), document.period().to());
        for (var table : document.tables()) {
            output.println(table.title());
            output.println(String.join(" | ", table.columns()));
            table.rows().forEach(row -> output.println(String.join(" | ", row)));
        }
    }
    public static void statements(PrintStream output, FinancialStatements statements) {
        var trial = statements.trialBalance();
        output.printf("REPORT PERIOD %s to %s; source periods=%s%n", trial.period().from(), trial.period().to(), trial.periodsClosed() ? "CLOSED" : "OPEN / provisional");
        output.println("TRIAL BALANCE (whole VND)");
        output.printf("%-12s %15s %15s %15s %15s %15s %15s%n", "Account", "Opening Dr", "Opening Cr", "Movement Dr", "Movement Cr", "Closing Dr", "Closing Cr");
        trial.rows().stream().filter(row -> !row.opening().equals(DebitCredit.ZERO) || !row.movements().equals(DebitCredit.ZERO) || !row.closing().equals(DebitCredit.ZERO))
                .forEach(row -> output.printf("%-12s %15s %15s %15s %15s %15s %15s%n", row.accountCode(), row.opening().debit(), row.opening().credit(),
                        row.movements().debit(), row.movements().credit(), row.closing().debit(), row.closing().credit()));
        output.printf("%-12s %15s %15s %15s %15s %15s %15s%n", "TOTAL", trial.openingTotals().debit(), trial.openingTotals().credit(),
                trial.movementTotals().debit(), trial.movementTotals().credit(), trial.closingTotals().debit(), trial.closingTotals().credit());
        output.println("INCOME STATEMENT (business postings; generated closing transfers excluded)");
        statements.incomeStatement().rows().forEach(row -> output.printf("%s %s %s = %s%n", row.type(), row.accountCode(), row.accountName(), row.amount()));
        output.printf("Revenue=%s; expenses=%s; profit=%s%n", statements.incomeStatement().revenue(), statements.incomeStatement().expense(), statements.incomeStatement().profit());
        output.println("BALANCE SHEET AS OF " + statements.balanceSheet().asOf());
        statements.balanceSheet().rows().forEach(row -> output.printf("%s %s %s %s = %s%s%n", row.section(), row.accountCode() == null ? "" : row.accountCode(),
                row.partnerCode() == null ? "" : row.partnerCode(), row.accountName(), row.amount(), row.reclassified() ? " (partner advance)" : ""));
        output.printf("Total assets=%s; liabilities=%s; equity=%s%n", statements.balanceSheet().assets(), statements.balanceSheet().liabilities(), statements.balanceSheet().equity());
    }
    public static void journal(PrintStream output, GeneralJournal journal) {
        output.println("GENERAL JOURNAL");
        journal.rows().forEach(row -> output.printf("%s %-24s #%d %-8s partner=%s fund=%s Dr=%s Cr=%s%s%n",
                row.postingDate(), row.reference(), row.lineNumber(), row.accountCode(), row.partnerCode() == null ? "-" : row.partnerCode(),
                row.moneyAccountCode() == null ? "-" : row.moneyAccountCode(), row.amounts().debit(), row.amounts().credit(), row.closing() ? " [closing]" : ""));
        output.printf("Entries=%d; total debit=%s; total credit=%s%n", journal.entryCount(), journal.totals().debit(), journal.totals().credit());
    }
    public static void ledger(PrintStream output, AccountLedger ledger) {
        output.printf("ACCOUNT LEDGER %s partner=%s fund=%s; opening debit-minus-credit=%s%n", ledger.query().accountCode(),
                ledger.query().partnerCode() == null ? "ALL" : ledger.query().partnerCode(), ledger.query().moneyAccountCode() == null ? "ALL" : ledger.query().moneyAccountCode(), ledger.openingBalance());
        ledger.rows().forEach(row -> output.printf("%s %-24s Dr=%s Cr=%s; running debit-minus-credit=%s%n", row.posting().postingDate(),
                row.posting().reference(), row.posting().amounts().debit(), row.posting().amounts().credit(), row.runningBalance()));
        output.println("Closing debit-minus-credit=" + ledger.closingBalance());
    }
}
