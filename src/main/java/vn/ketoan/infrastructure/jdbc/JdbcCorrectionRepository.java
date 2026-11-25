package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.CorrectionRepository;
import vn.ketoan.domain.CorrectionKind;
import vn.ketoan.domain.CorrectionRecord;

final class JdbcCorrectionRepository implements CorrectionRepository {

    private final JdbcContext context;

    JdbcCorrectionRepository(JdbcContext context) { this.context = context; }

    @Override public Optional<CorrectionRecord> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_corrections WHERE id=?", this::read,
                Objects.requireNonNull(id, "Correction id is required")));
    }
    @Override public Optional<CorrectionRecord> findByTargetDocumentId(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_corrections WHERE target_document_id=?", this::read,
                Objects.requireNonNull(id, "Target document id is required")));
    }
    @Override public List<CorrectionRecord> findAll() {
        return context.query("SELECT * FROM acc_corrections ORDER BY created_at,id", this::read);
    }
    @Override public void append(CorrectionRecord value) {
        context.mutate(() -> context.update("""
                INSERT INTO acc_corrections(id,source_document_id,target_document_id,kind,reason,created_by,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, value.id(), value.sourceDocumentId(), value.targetDocumentId(), value.kind(), value.reason(), value.createdBy(), value.createdAt()));
    }

    private CorrectionRecord read(ResultSet row) throws SQLException {
        return new CorrectionRecord(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("source_document_id")),
                UUID.fromString(row.getString("target_document_id")), CorrectionKind.valueOf(row.getString("kind")),
                row.getString("reason"), row.getLong("created_by"), Instant.parse(row.getString("created_at")));
    }
}
