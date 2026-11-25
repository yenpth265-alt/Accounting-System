package vn.ketoan;

import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.ReportingService;
import vn.ketoan.domain.report.ReportPeriod;
import java.time.YearMonth;

public final class ReportingTestData {
    public static final ReportPeriod NOVEMBER = ReportPeriod.month(YearMonth.from(VoucherTestData.DATE));
    private ReportingTestData() { }
    public static void seedBusiness(UnitOfWork unit) {
        ClosingTestData.seed(unit);
        SettlementTestData.sale(unit, "REPORT_SALE", 10_000_000);
        ClosingTestData.expense(unit, "REPORT_EXPENSE", 3_000_000);
        SettlementTestData.receipt(unit, "REPORT_RECEIPT", 4_000_000);
    }
    public static ReportingService reporting(UnitOfWork unit) { return new ReportingService(unit); }
    public static void closeNovember(UnitOfWork unit) {
        var service = ClosingTestData.closing(unit);
        var batch = service.prepare(ClosingTestData.request("REPORT_CLOSE"), WorkflowTestData.OWNER);
        service.approve(batch.id(), WorkflowTestData.CHIEF);
        service.close(batch.id(), WorkflowTestData.CHIEF);
    }
}
