package vn.ketoan.domain.report;

public final class CompleteReportGenerator implements ReportGenerator<ReportPeriod, ReportPackage> {
    private final ReportGenerator<ReportPeriod, GeneralJournal> journal;
    private final ReportGenerator<ReportPeriod, TrialBalance> trial;
    private final ReportGenerator<ReportPeriod, IncomeStatement> income;
    private final ReportGenerator<java.time.LocalDate, BalanceSheet> balance;
    private final ReportGenerator<ReportPeriod, CashFlowStatement> cash;
    private final ReportGenerator<ReportPeriod, ReportNotes> notes;
    public CompleteReportGenerator() { this(new GeneralJournalGenerator(), new TrialBalanceGenerator(), new IncomeStatementGenerator(), new BalanceSheetGenerator(), new CashFlowGenerator(), new ReportNotesGenerator()); }
    public CompleteReportGenerator(ReportGenerator<ReportPeriod, GeneralJournal> journal, ReportGenerator<ReportPeriod, TrialBalance> trial,
                                    ReportGenerator<ReportPeriod, IncomeStatement> income, ReportGenerator<java.time.LocalDate, BalanceSheet> balance,
                                    ReportGenerator<ReportPeriod, CashFlowStatement> cash, ReportGenerator<ReportPeriod, ReportNotes> notes) {
        this.journal = java.util.Objects.requireNonNull(journal); this.trial = java.util.Objects.requireNonNull(trial);
        this.income = java.util.Objects.requireNonNull(income); this.balance = java.util.Objects.requireNonNull(balance);
        this.cash = java.util.Objects.requireNonNull(cash); this.notes = java.util.Objects.requireNonNull(notes);
    }
    @Override public ReportPackage generate(ReportContext context, ReportPeriod period) {
        var financials = new FinancialStatements(trial.generate(context, period), income.generate(context, period), balance.generate(context, period.to()));
        return new ReportPackage(financials, journal.generate(context, period), cash.generate(context, period), notes.generate(context, period),
                context.rows().stream().filter(row -> !row.postingDate().isAfter(period.to())).toList());
    }
}
