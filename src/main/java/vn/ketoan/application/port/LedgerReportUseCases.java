package vn.ketoan.application.port;

import vn.ketoan.domain.report.AccountLedger;
import vn.ketoan.domain.report.GeneralJournal;
import vn.ketoan.domain.report.LedgerQuery;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.TrialBalance;

public interface LedgerReportUseCases {
    GeneralJournal journal(ReportPeriod period, long actorId);
    AccountLedger ledger(LedgerQuery query, long actorId);
    TrialBalance trialBalance(ReportPeriod period, long actorId);
}
