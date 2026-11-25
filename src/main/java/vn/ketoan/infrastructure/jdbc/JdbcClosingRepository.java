package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.application.port.ClosingRepository;
import vn.ketoan.domain.ClosingAction;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingEvent;
import vn.ketoan.domain.ClosingRequest;
import vn.ketoan.domain.value.Money;

final class JdbcClosingRepository implements ClosingRepository {
    private final JdbcContext context;
    JdbcClosingRepository(JdbcContext context) { this.context = context; }
    @Override public Optional<ClosingBatch> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_closing_batches WHERE id=?", this::read, Objects.requireNonNull(id)));
    }
    @Override public Optional<ClosingBatch> findByDocumentId(UUID id) {
        return context.guard(() -> ClosingRepository.super.findByDocumentId(id));
    }
    @Override public List<ClosingBatch> findAll() { return context.query("SELECT * FROM acc_closing_batches ORDER BY month_key,reference", this::read); }
    @Override public void append(ClosingBatch batch) {
        context.mutate(() -> {
            var request = batch.request();
            context.update("""
                    INSERT INTO acc_closing_batches(id,reference,month_key,result_account_code,retained_account_code,
                    source_checksum,revenue,expense,profit,prepared_by,prepared_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, batch.id(), request.reference(), request.month().toString(), request.resultAccountCode(), request.retainedEarningsAccountCode(),
                    batch.sourceChecksum(), batch.revenue(), batch.expense(), batch.profit(), batch.preparedBy(), batch.preparedAt());
            for (int index = 0; index < batch.sourceEntryIds().size(); index++) {
                context.update("INSERT INTO acc_closing_sources(batch_id,position_no,entry_id) VALUES (?,?,?)", batch.id(), index, batch.sourceEntryIds().get(index));
            }
            for (int index = 0; index < batch.documentIds().size(); index++) {
                context.update("INSERT INTO acc_closing_documents(batch_id,position_no,document_id) VALUES (?,?,?)", batch.id(), index, batch.documentIds().get(index));
            }
        });
    }
    @Override public List<ClosingEvent> findEvents() {
        return context.query("SELECT * FROM acc_closing_events ORDER BY batch_id,occurred_at,action", row -> new ClosingEvent(
                UUID.fromString(row.getString("batch_id")), ClosingAction.valueOf(row.getString("action")), row.getString("reason"),
                row.getLong("actor_id"), Instant.parse(row.getString("occurred_at"))));
    }
    @Override public void appendEvent(ClosingEvent event) {
        context.mutate(() -> context.update("INSERT INTO acc_closing_events(batch_id,action,reason,actor_id,occurred_at) VALUES (?,?,?,?,?)",
                event.batchId(), event.action(), event.reason(), event.actorId(), event.occurredAt()));
    }
    private ClosingBatch read(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("id"));
        var request = new ClosingRequest(id, row.getString("reference"), YearMonth.parse(row.getString("month_key")),
                row.getString("result_account_code"), row.getString("retained_account_code"));
        List<UUID> sources = context.query("SELECT entry_id FROM acc_closing_sources WHERE batch_id=? ORDER BY position_no",
                value -> UUID.fromString(value.getString(1)), id);
        List<UUID> documents = context.query("SELECT document_id FROM acc_closing_documents WHERE batch_id=? ORDER BY position_no",
                value -> UUID.fromString(value.getString(1)), id);
        return new ClosingBatch(request, sources, documents, row.getString("source_checksum"), Money.of(row.getBigDecimal("revenue")),
                Money.of(row.getBigDecimal("expense")), Money.of(row.getBigDecimal("profit")), row.getLong("prepared_by"), Instant.parse(row.getString("prepared_at")));
    }
}
