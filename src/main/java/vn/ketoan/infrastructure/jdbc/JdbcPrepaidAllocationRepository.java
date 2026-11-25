package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.PrepaidAllocationRepository;
import vn.ketoan.domain.PrepaidAllocationRecord;

final class JdbcPrepaidAllocationRepository implements PrepaidAllocationRepository {

    private final JdbcContext context;

    JdbcPrepaidAllocationRepository(JdbcContext context) { this.context = context; }

    @Override public Optional<PrepaidAllocationRecord> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_prepaid_allocations WHERE id=?", this::read,
                Objects.requireNonNull(id, "Prepaid allocation id is required")));
    }
    @Override public Optional<PrepaidAllocationRecord> findByTargetDocumentId(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_prepaid_allocations WHERE target_document_id=?", this::read,
                Objects.requireNonNull(id, "Prepaid target document id is required")));
    }
    @Override public List<PrepaidAllocationRecord> findAll() {
        return context.query("SELECT * FROM acc_prepaid_allocations ORDER BY plan_id,month_key,id", this::read);
    }
    @Override public void append(PrepaidAllocationRecord value) {
        context.mutate(() -> context.update("""
                INSERT INTO acc_prepaid_allocations(id,plan_id,month_key,target_document_id,created_by,created_at)
                VALUES (?,?,?,?,?,?)
                """, value.id(), value.planId(), value.month().toString(), value.targetDocumentId(), value.createdBy(), value.createdAt()));
    }

    private PrepaidAllocationRecord read(ResultSet row) throws SQLException {
        return new PrepaidAllocationRecord(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("plan_id")),
                YearMonth.parse(row.getString("month_key")), UUID.fromString(row.getString("target_document_id")),
                row.getLong("created_by"), Instant.parse(row.getString("created_at")));
    }
}
