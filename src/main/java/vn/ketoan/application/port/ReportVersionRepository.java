package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.report.ReportVersion;

public interface ReportVersionRepository {
    Optional<ReportVersion> findById(UUID id);
    List<ReportVersion> findAll();
    void append(ReportVersion version);
    List<ReportPublication> findPublications();
    void appendPublication(ReportPublication publication);
}
