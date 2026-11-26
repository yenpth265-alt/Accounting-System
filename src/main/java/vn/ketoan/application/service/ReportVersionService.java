package vn.ketoan.application.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.ketoan.application.port.ReportVersionUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.report.CompleteReportGenerator;
import vn.ketoan.domain.report.ReportDocumentBuilder;
import vn.ketoan.domain.report.ReportGenerator;
import vn.ketoan.domain.report.ReportPackage;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.report.ReportVersion;
import vn.ketoan.domain.report.SaveReportVersionRequest;
import vn.ketoan.domain.report.TabularReportDocumentBuilder;

public final class ReportVersionService implements ReportVersionUseCases {
    private final UnitOfWork transactions;
    private final Clock clock;
    private final ReportGenerator<ReportPeriod, ReportPackage> generator;
    private final ReportDocumentBuilder builder;
    public ReportVersionService(UnitOfWork transactions, Clock clock) { this(transactions, clock, new CompleteReportGenerator(), new TabularReportDocumentBuilder()); }
    public ReportVersionService(UnitOfWork transactions, Clock clock, ReportGenerator<ReportPeriod, ReportPackage> generator, ReportDocumentBuilder builder) {
        this.transactions = Objects.requireNonNull(transactions); this.clock = Objects.requireNonNull(clock);
        this.generator = Objects.requireNonNull(generator); this.builder = Objects.requireNonNull(builder);
    }
    @Override public ReportVersion save(SaveReportVersionRequest request, long actorId) {
        Objects.requireNonNull(request);
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.SAVE_REPORT_VERSION);
            var existing = session.reportVersions().findById(request.id());
            if (existing.isPresent()) {
                var stored = existing.orElseThrow();
                if (!stored.period().equals(request.period()) || !stored.title().equals(request.title()) || stored.createdBy() != actorId) {
                    throw new IllegalArgumentException("Report request id was already used for different input");
                }
                return stored;
            }
            var report = generator.generate(ReportContexts.capture(session), request.period());
            var document = builder.build(report);
            int version = Math.incrementExact(session.reportVersions().findAll().stream().filter(value -> value.period().equals(request.period())).mapToInt(ReportVersion::version).max().orElse(0));
            var stored = new ReportVersion(request.id(), request.period(), version, request.title(), report.sourceChecksum(document), document.fingerprint(), document,
                    report.financials().trialBalance().periodsClosed(), report.cashFlow().complete(), actorId, clock.instant());
            session.reportVersions().append(stored);
            return stored;
        });
    }
    @Override public ReportVersion get(UUID id, long actorId) {
        Objects.requireNonNull(id);
        return transactions.execute(session -> { AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS); return session.reportVersions().findById(id).orElseThrow(() -> new IllegalArgumentException("Report version not found")); });
    }
    @Override public List<ReportVersion> versions(ReportPeriod period, long actorId) {
        Objects.requireNonNull(period);
        return transactions.execute(session -> { AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS);
            return session.reportVersions().findAll().stream().filter(value -> value.period().equals(period)).sorted(java.util.Comparator.comparingInt(ReportVersion::version)).toList(); });
    }
    @Override public ReportPublication publish(UUID id, String reviewNote, long actorId) {
        Objects.requireNonNull(id);
        return transactions.execute(session -> {
            AccountingBooks.actor(session, actorId, Permission.PUBLISH_REPORT);
            var version = session.reportVersions().findById(id).orElseThrow(() -> new IllegalArgumentException("Report version not found"));
            if (version.createdBy() == actorId) { throw new AccessDeniedException("The preparer cannot publish their own report"); }
            var publication = new ReportPublication(id, reviewNote, actorId, clock.instant());
            var publications = session.reportVersions().findPublications();
            var existing = publications.stream().filter(value -> value.versionId().equals(id)).findFirst();
            if (existing.isPresent()) {
                if (!existing.orElseThrow().reviewNote().equals(publication.reviewNote())) { throw new IllegalArgumentException("Report was already published with another review note"); }
                return existing.orElseThrow();
            }
            if (!version.periodsClosed() || !version.cashFlowComplete()) { throw new IllegalStateException("Only a closed-period snapshot with fully classified cash flows can be published"); }
            var current = generator.generate(ReportContexts.capture(session), version.period());
            if (!current.financials().trialBalance().periodsClosed() || !current.cashFlow().complete()
                    || !version.sourceChecksum().equals(current.sourceChecksum(builder.build(current)))) {
                throw new IllegalStateException("The report source changed; save and review a new version");
            }
            var publishedIds = publications.stream().map(ReportPublication::versionId).collect(java.util.stream.Collectors.toSet());
            if (session.reportVersions().findAll().stream().anyMatch(value -> value.period().equals(version.period())
                    && value.version() >= version.version() && publishedIds.contains(value.id()))) { throw new IllegalStateException("A newer report version has already been published"); }
            session.reportVersions().appendPublication(publication);
            return publication;
        });
    }
    @Override public List<ReportPublication> publications(long actorId) {
        return transactions.execute(session -> { AccountingBooks.actor(session, actorId, Permission.VIEW_VOUCHERS); return session.reportVersions().findPublications(); });
    }
}
