package vn.ketoan.application.service;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Function;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.FinancialReportUseCases;
import vn.ketoan.application.port.LedgerReportUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.SupplementaryReportUseCases;
import vn.ketoan.domain.report.CashFlowGenerator;
import vn.ketoan.domain.report.CashFlowStatement;
import vn.ketoan.domain.report.CompleteReportGenerator;
import vn.ketoan.domain.report.ReportNotes;
import vn.ketoan.domain.report.ReportNotesGenerator;
import vn.ketoan.domain.report.ReportPackage;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.report.AccountLedger;
import vn.ketoan.domain.report.AccountLedgerGenerator;
import vn.ketoan.domain.report.BalanceSheet;
import vn.ketoan.domain.report.BalanceSheetGenerator;
import vn.ketoan.domain.report.FinancialStatements;
import vn.ketoan.domain.report.GeneralJournal;
import vn.ketoan.domain.report.GeneralJournalGenerator;
import vn.ketoan.domain.report.IncomeStatement;
import vn.ketoan.domain.report.IncomeStatementGenerator;
import vn.ketoan.domain.report.LedgerQuery;
import vn.ketoan.domain.report.ReportContext;
import vn.ketoan.domain.report.ReportGenerator;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.TrialBalance;
import vn.ketoan.domain.report.TrialBalanceGenerator;

/** Authorizes reads, captures one source snapshot and delegates calculation through report interfaces. */
public final class ReportingService implements LedgerReportUseCases, FinancialReportUseCases, SupplementaryReportUseCases {
    private final UnitOfWork transactions;
    private final ReportGenerator<ReportPeriod, GeneralJournal> journal;
    private final ReportGenerator<LedgerQuery, AccountLedger> ledger;
    private final ReportGenerator<ReportPeriod, TrialBalance> trialBalance;
    private final ReportGenerator<ReportPeriod, IncomeStatement> incomeStatement;
    private final ReportGenerator<LocalDate, BalanceSheet> balanceSheet;
    private final ReportGenerator<ReportPeriod, CashFlowStatement> cashFlow;
    private final ReportGenerator<ReportPeriod, ReportNotes> notes;
    private final ReportGenerator<ReportPeriod, ReportPackage> complete;

    public ReportingService(UnitOfWork transactions) {
        this(transactions, new GeneralJournalGenerator(), new AccountLedgerGenerator(), new TrialBalanceGenerator(),
                new IncomeStatementGenerator(), new BalanceSheetGenerator());
    }
    public ReportingService(UnitOfWork transactions, ReportGenerator<ReportPeriod, GeneralJournal> journal,
                            ReportGenerator<LedgerQuery, AccountLedger> ledger, ReportGenerator<ReportPeriod, TrialBalance> trialBalance,
                            ReportGenerator<ReportPeriod, IncomeStatement> incomeStatement, ReportGenerator<LocalDate, BalanceSheet> balanceSheet) {
        this(transactions, journal, ledger, trialBalance, incomeStatement, balanceSheet, new CashFlowGenerator(), new ReportNotesGenerator());
    }
    public ReportingService(UnitOfWork transactions, ReportGenerator<ReportPeriod, GeneralJournal> journal,
                            ReportGenerator<LedgerQuery, AccountLedger> ledger, ReportGenerator<ReportPeriod, TrialBalance> trialBalance,
                            ReportGenerator<ReportPeriod, IncomeStatement> incomeStatement, ReportGenerator<LocalDate, BalanceSheet> balanceSheet,
                            ReportGenerator<ReportPeriod, CashFlowStatement> cashFlow, ReportGenerator<ReportPeriod, ReportNotes> notes) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.journal = Objects.requireNonNull(journal, "Journal generator is required");
        this.ledger = Objects.requireNonNull(ledger, "Ledger generator is required");
        this.trialBalance = Objects.requireNonNull(trialBalance, "Trial balance generator is required");
        this.incomeStatement = Objects.requireNonNull(incomeStatement, "Income statement generator is required");
        this.balanceSheet = Objects.requireNonNull(balanceSheet, "Balance sheet generator is required");
        this.cashFlow = Objects.requireNonNull(cashFlow, "Cash flow generator is required");
        this.notes = Objects.requireNonNull(notes, "Report notes generator is required");
        this.complete = new CompleteReportGenerator(journal, trialBalance, incomeStatement, balanceSheet, cashFlow, notes);
    }
    @Override public GeneralJournal journal(ReportPeriod period, long actorId) { return generate(period, actorId, journal); }
    @Override public AccountLedger ledger(LedgerQuery query, long actorId) { return generate(query, actorId, ledger); }
    @Override public TrialBalance trialBalance(ReportPeriod period, long actorId) { return generate(period, actorId, trialBalance); }
    @Override public IncomeStatement incomeStatement(ReportPeriod period, long actorId) { return generate(period, actorId, incomeStatement); }
    @Override public BalanceSheet balanceSheet(LocalDate asOf, long actorId) { return generate(asOf, actorId, balanceSheet); }
    @Override public CashFlowStatement cashFlow(ReportPeriod period, long actorId) { return generate(period, actorId, cashFlow); }
    @Override public ReportNotes notes(ReportPeriod period, long actorId) { return generate(period, actorId, notes); }
    @Override public ReportPackage complete(ReportPeriod period, long actorId) { return generate(period, actorId, complete); }
    @Override public FinancialStatements statements(ReportPeriod period, long actorId) {
        Objects.requireNonNull(period, "Report period is required");
        return read(actorId, context -> new FinancialStatements(trialBalance.generate(context, period),
                incomeStatement.generate(context, period), balanceSheet.generate(context, period.to())));
    }
    private <Q, R> R generate(Q query, long actorId, ReportGenerator<Q, R> generator) {
        Objects.requireNonNull(query, "Report query is required");
        return read(actorId, context -> generator.generate(context, query));
    }
    private <R> R read(long actorId, Function<ReportContext, R> query) {
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            return query.apply(context(session));
        });
    }
    private ReportContext context(AccountingSession session) {
        return ReportContexts.capture(session);
    }
}
