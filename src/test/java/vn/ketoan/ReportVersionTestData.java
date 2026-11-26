package vn.ketoan;

import java.util.List;
import java.util.UUID;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.CashFlowClassificationService;
import vn.ketoan.application.service.ReportVersionService;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.report.CashFlowActivity;
import vn.ketoan.domain.report.CashFlowClassificationRequest;
import vn.ketoan.domain.report.CashFlowDirection;
import vn.ketoan.domain.report.CashFlowSplit;
import vn.ketoan.domain.report.SaveReportVersionRequest;
import vn.ketoan.domain.value.Money;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.ReportingTestData.NOVEMBER;

public final class ReportVersionTestData {
    private ReportVersionTestData() { }
    public static CashFlowClassificationService classifications(UnitOfWork unit) { return new CashFlowClassificationService(unit, CLOCK); }
    public static ReportVersionService versions(UnitOfWork unit) { return new ReportVersionService(unit, CLOCK); }
    public static JournalEntry entry(UnitOfWork unit, String reference) {
        return unit.execute(session -> session.journal().findAll().stream().filter(value -> value.getContent().reference().equals(reference)).findFirst().orElseThrow());
    }
    public static CashFlowClassificationRequest classification(JournalEntry entry, int revision, CashFlowActivity activity, CashFlowDirection direction, long amount) {
        return new CashFlowClassificationRequest(UUID.randomUUID(), entry.getId(), revision, "Business cash flow classification", Money.ZERO,
                List.of(new CashFlowSplit(activity, direction, Money.of(amount))));
    }
    public static SaveReportVersionRequest snapshot(String title) { return new SaveReportVersionRequest(UUID.randomUUID(), NOVEMBER, title); }
}
