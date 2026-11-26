package vn.ketoan.domain.report;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReportVersion(UUID id, ReportPeriod period, int version, String title, String sourceChecksum,
                             String documentChecksum, ReportDocument document, boolean periodsClosed,
                             boolean cashFlowComplete, long createdBy, Instant createdAt) {
    public ReportVersion {
        Objects.requireNonNull(id); Objects.requireNonNull(period); Objects.requireNonNull(document); Objects.requireNonNull(createdAt);
        title = ReportText.required(title, 160, "Report title");
        if (version < 1 || createdBy <= 0 || sourceChecksum == null || !sourceChecksum.matches("[a-f0-9]{64}")
                || !period.equals(document.period()) || !document.fingerprint().equals(documentChecksum)) {
            throw new IllegalArgumentException("Stored report version, source or document checksum is inconsistent");
        }
    }
}
