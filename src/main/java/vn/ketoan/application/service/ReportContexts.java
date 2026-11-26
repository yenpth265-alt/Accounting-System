package vn.ketoan.application.service;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.domain.report.ReportContext;
import vn.ketoan.domain.report.ReportEvidence;

final class ReportContexts {
    private ReportContexts() { }
    static ReportContext capture(AccountingSession session) {
        return new ReportContext(session.accounts().findAll(), session.partners().findAll(), session.moneyAccounts().findAll(), session.periods().findAll(),
                session.journal().findAll(), session.closings().findAll(), new ReportEvidence(session.documents().findAll(), session.corrections().findAll(),
                session.prepaidPlans().findAll(), session.prepaidPlans().findCancellations(), session.prepaidAllocations().findAll(), session.cashFlowClassifications().findAll()));
    }
}
