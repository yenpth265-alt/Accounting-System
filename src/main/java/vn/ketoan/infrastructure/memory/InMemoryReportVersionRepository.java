package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.application.port.ReportVersionRepository;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.report.ReportVersion;

public final class InMemoryReportVersionRepository implements ReportVersionRepository {
    private final Map<UUID, ReportVersion> versions = new LinkedHashMap<>();
    private final Map<UUID, ReportPublication> publications = new LinkedHashMap<>();
    @Override public Optional<ReportVersion> findById(UUID id) { return Optional.ofNullable(versions.get(java.util.Objects.requireNonNull(id))); }
    @Override public List<ReportVersion> findAll() { return List.copyOf(versions.values()); }
    @Override public List<ReportPublication> findPublications() { return List.copyOf(publications.values()); }
    @Override public void append(ReportVersion value) {
        if (versions.containsKey(value.id()) || versions.values().stream().anyMatch(existing -> existing.period().equals(value.period()) && existing.version() == value.version())) {
            throw new IllegalArgumentException("Report version id and period/version must be unique");
        }
        versions.put(value.id(), value);
    }
    @Override public void appendPublication(ReportPublication value) {
        if (!versions.containsKey(value.versionId()) || publications.containsKey(value.versionId())) { throw new IllegalArgumentException("Report publication must reference a unique stored version"); }
        publications.put(value.versionId(), value);
    }
}
