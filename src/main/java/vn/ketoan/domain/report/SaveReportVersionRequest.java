package vn.ketoan.domain.report;

import java.util.Objects;
import java.util.UUID;

public record SaveReportVersionRequest(UUID id, ReportPeriod period, String title) {
    public SaveReportVersionRequest {
        Objects.requireNonNull(id); Objects.requireNonNull(period);
        title = ReportText.required(title, 160, "Report title");
    }
}
