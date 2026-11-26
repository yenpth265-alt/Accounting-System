package vn.ketoan.domain.report;

import java.time.LocalDate;
import vn.ketoan.domain.CorrectionKind;

public record CorrectionReportNote(String sourceReference, String targetReference, CorrectionKind kind, LocalDate postingDate, String reason) {
    public CorrectionReportNote {
        sourceReference = ReportText.required(sourceReference, 32, "Source reference");
        targetReference = ReportText.required(targetReference, 32, "Target reference");
        java.util.Objects.requireNonNull(kind);
        ReportPeriod.requireDate(postingDate);
        reason = ReportText.required(reason, 255, "Correction reason");
    }
}
