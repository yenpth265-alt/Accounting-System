package vn.ketoan.application.port;

import vn.ketoan.domain.report.CashFlowStatement;
import vn.ketoan.domain.report.ReportNotes;
import vn.ketoan.domain.report.ReportPackage;
import vn.ketoan.domain.report.ReportPeriod;

public interface SupplementaryReportUseCases {
    CashFlowStatement cashFlow(ReportPeriod period, long actorId);
    ReportNotes notes(ReportPeriod period, long actorId);
    ReportPackage complete(ReportPeriod period, long actorId);
}
