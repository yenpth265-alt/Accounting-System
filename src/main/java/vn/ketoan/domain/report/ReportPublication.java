package vn.ketoan.domain.report;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReportPublication(UUID versionId, String reviewNote, long publishedBy, Instant publishedAt) {
    public ReportPublication {
        Objects.requireNonNull(versionId); Objects.requireNonNull(publishedAt);
        reviewNote = ReportText.required(reviewNote, 255, "Report review note");
        if (publishedBy <= 0) { throw new IllegalArgumentException("Report reviewer must be positive"); }
    }
}
