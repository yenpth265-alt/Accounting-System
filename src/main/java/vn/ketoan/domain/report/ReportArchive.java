package vn.ketoan.domain.report;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ReportArchive {
    public ReportArchive(Collection<ReportVersion> versions, Collection<ReportPublication> publications) {
        var byId = versions.stream().collect(Collectors.toMap(ReportVersion::id, Function.identity()));
        Map<ReportPeriod, ReportVersion> previous = new HashMap<>();
        for (var value : versions.stream().sorted(Comparator.comparingInt(ReportVersion::version)).toList()) {
            var prior = previous.get(value.period());
            if (value.version() != (prior == null ? 1 : Math.incrementExact(prior.version()))
                    || (prior != null && value.createdAt().isBefore(prior.createdAt()))) {
                throw new IllegalArgumentException("Report versions require contiguous numbers and chronological creation");
            }
            previous.put(value.period(), value);
        }
        var seen = new HashSet<UUID>();
        for (var publication : publications) {
            var version = byId.get(publication.versionId());
            if (version == null || !seen.add(publication.versionId()) || version.createdBy() == publication.publishedBy()
                    || publication.publishedAt().isBefore(version.createdAt()) || !version.periodsClosed() || !version.cashFlowComplete()) {
                throw new IllegalStateException("Published reports require an independently reviewed, complete and closed-period snapshot");
            }
        }
        Map<ReportPeriod, ReportPublication> latest = new HashMap<>();
        publications.stream().sorted(Comparator.comparingInt(value -> byId.get(value.versionId()).version())).forEach(value -> {
            var period = byId.get(value.versionId()).period();
            var prior = latest.put(period, value);
            if (prior != null && value.publishedAt().isBefore(prior.publishedAt())) { throw new IllegalStateException("Report publications must follow version order"); }
        });
    }
}
