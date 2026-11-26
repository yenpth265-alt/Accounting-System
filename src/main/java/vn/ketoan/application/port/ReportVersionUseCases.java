package vn.ketoan.application.port;

import java.util.List;
import java.util.UUID;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.report.ReportVersion;
import vn.ketoan.domain.report.SaveReportVersionRequest;

public interface ReportVersionUseCases {
    ReportVersion save(SaveReportVersionRequest request, long actorId);
    ReportVersion get(UUID id, long actorId);
    List<ReportVersion> versions(ReportPeriod period, long actorId);
    ReportPublication publish(UUID id, String reviewNote, long actorId);
    List<ReportPublication> publications(long actorId);
}
