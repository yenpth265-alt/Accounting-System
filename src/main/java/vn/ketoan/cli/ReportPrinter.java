package vn.ketoan.cli;

import java.io.PrintStream;
import vn.ketoan.domain.report.AccountLedger;
import vn.ketoan.domain.report.DebitCredit;
import vn.ketoan.domain.report.FinancialStatements;
import vn.ketoan.domain.report.GeneralJournal;

/** Console presentation only. Calculations and storage stay outside this class. */
public final class ReportPrinter {
    private ReportPrinter() { }
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
