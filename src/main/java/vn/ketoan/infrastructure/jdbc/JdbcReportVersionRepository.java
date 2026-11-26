package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.application.port.ReportDocumentCodec;
import vn.ketoan.application.port.ReportVersionRepository;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.report.ReportVersion;

final class JdbcReportVersionRepository implements ReportVersionRepository {
    private final JdbcContext context;
    private final ReportDocumentCodec codec;
    JdbcReportVersionRepository(JdbcContext context, ReportDocumentCodec codec) { this.context = context; this.codec = java.util.Objects.requireNonNull(codec); }
    @Override public Optional<ReportVersion> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_report_versions WHERE id=?", this::read, java.util.Objects.requireNonNull(id)));
    }
    @Override public List<ReportVersion> findAll() { return context.query("SELECT * FROM acc_report_versions ORDER BY period_from,period_to,version_no", this::read); }
    @Override public void append(ReportVersion value) {
        context.mutate(() -> context.update("""
                INSERT INTO acc_report_versions(id,period_from,period_to,version_no,title,source_checksum,document_checksum,document_xml,
                periods_closed,cash_flow_complete,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.id(), value.period().from(), value.period().to(), value.version(), value.title(), value.sourceChecksum(), value.documentChecksum(),
                codec.encode(value.document()), value.periodsClosed(), value.cashFlowComplete(), value.createdBy(), value.createdAt()));
    }
    @Override public List<ReportPublication> findPublications() {
        return context.query("SELECT * FROM acc_report_publications ORDER BY published_at,version_id", row -> new ReportPublication(UUID.fromString(row.getString("version_id")),
                row.getString("review_note"), row.getLong("published_by"), Instant.parse(row.getString("published_at"))));
    }
    @Override public void appendPublication(ReportPublication value) {
        context.mutate(() -> context.update("INSERT INTO acc_report_publications(version_id,review_note,published_by,published_at) VALUES (?,?,?,?)",
                value.versionId(), value.reviewNote(), value.publishedBy(), value.publishedAt()));
    }
    private ReportVersion read(ResultSet row) throws SQLException {
        var period = new ReportPeriod(row.getDate("period_from").toLocalDate(), row.getDate("period_to").toLocalDate());
        return new ReportVersion(UUID.fromString(row.getString("id")), period, row.getInt("version_no"), row.getString("title"), row.getString("source_checksum"),
                row.getString("document_checksum"), codec.decode(row.getString("document_xml")), row.getBoolean("periods_closed"), row.getBoolean("cash_flow_complete"),
                row.getLong("created_by"), Instant.parse(row.getString("created_at")));
    }
}
